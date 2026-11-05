/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIonut01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.dot.gallery.core.logging.printDebug
import com.dot.gallery.core.logging.printError
import com.dot.gallery.data.data_source.KeychainHolder
import com.dot.gallery.domain.model.Media
import com.dot.gallery.domain.model.Vault
import com.dot.gallery.domain.util.getUri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Encrypted, pre-generated thumbnails for vault media.
 *
 * Vault files are whole-file encrypted, so before this store existed every grid cell paid a
 * full decrypt (and often a full-size bitmap decode) to draw a 300 px tile. At 2-3k items that
 * is the dominant cost of browsing a vault, and it is pure waste: the tile never needs more
 * than a few hundred pixels.
 *
 * During import (and lazily on the first miss) this store freezes each item down to a small
 * JPEG and writes it encrypted with the vault's own data key into a hidden folder:
 *
 *     filesDir/.vault_thumbs/<vaultUuid>/<mediaId>.thumb
 *
 * The layout deliberately mirrors `filesDir/<vaultUuid>/<mediaId>.enc` so existing decryption
 * helpers (which derive the vault id from the parent folder name) work unchanged.
 *
 * Notes:
 * - Best effort by design: any format the platform cannot decode simply gets no thumbnail and
 *   the UI falls back to the original file. Thumbnail failures must never fail an import.
 * - GIFs get a static first-frame thumbnail. The grid uses it while scrolling and switches to
 *   the animated decode once the list is idle, so animation is preserved.
 * - The store is encrypted, dot-prefixed and never walked by vault listings, so it is both
 *   unrecognisable on disk and safe from accidental "restore all" operations.
 */
class VaultThumbnailStore(context: Context) {

    private val appContext = context.applicationContext
    private val keychain by lazy { KeychainHolder(appContext) }

    /** Vault-relative thumbnail file. */
    fun thumbnailFile(vault: Vault, mediaId: Long): File =
        keychain.run { vault.thumbnailFile(mediaId) }

    fun has(vault: Vault, mediaId: Long): Boolean =
        thumbnailFile(vault, mediaId).let { it.isFile && it.length() > 0 }

    /**
     * Create the thumbnail for [media] if it does not exist yet. Returns true when a thumbnail
     * exists afterwards (either freshly written or already present).
     */
    fun ensure(vault: Vault, media: Media): Boolean {
        val target = thumbnailFile(vault, media.id)
        if (target.isFile && target.length() > 0) return true
        return generate(vault, media)
    }

    /** Force-regenerate [media]'s thumbnail. Returns true on success. */
    fun generate(vault: Vault, media: Media): Boolean {
        val uri = runCatching { media.getUri() }.getOrNull() ?: return false
        val scheme = uri.scheme
        if (scheme != null && scheme != "content" && scheme != "file") return false

        val isVideo = media.mimeType.startsWith("video", ignoreCase = true)
        val bitmap = try {
            if (isVideo) decodeVideoFrame(uri) else decodeImage(uri)
        } catch (t: Throwable) {
            printDebug(TAG, "thumb decode failed for ${media.id}: ${t.message}")
            null
        } ?: return false

        return writeBitmap(vault, media.id, bitmap)
    }

    /**
     * Lazily freeze a vault item that was encrypted before this store existed (or whose import
     * freeze failed), decoding straight from [open].
     *
     * [open] is invoked a few times (bounds, decode, EXIF) and must return a fresh stream of the
     * decrypted original each time. Only still images should call this: an undecodable payload
     * just returns false, but a video would need a full decrypt first.
     */
    fun ensureFromStream(vault: Vault, mediaId: Long, open: () -> InputStream?): Boolean {
        if (has(vault, mediaId)) return true
        return try {
            val bitmap = decodeFromStream(open) ?: return false
            writeBitmap(vault, mediaId, bitmap)
        } catch (t: Throwable) {
            printDebug(TAG, "lazy thumb decode failed for $mediaId: ${t.message}")
            false
        }
    }

    /** Same as [ensureFromStream] but for callers that already hold the decrypted bytes. */
    fun ensureFromBytes(vault: Vault, mediaId: Long, bytes: ByteArray): Boolean {
        if (has(vault, mediaId)) return true
        return try {
            val bitmap = decodeFromBytes(bytes) ?: return false
            writeBitmap(vault, mediaId, bitmap)
        } catch (t: Throwable) {
            printDebug(TAG, "lazy thumb decode failed for $mediaId: ${t.message}")
            false
        }
    }

    /** Compress [bitmap] to the tile format, encrypt it and publish it atomically. */
    private fun writeBitmap(vault: Vault, mediaId: Long, bitmap: Bitmap): Boolean = try {
        val scaled = scaleDown(bitmap)
        val jpeg = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, jpeg)) {
            false
        } else {
            writeEncrypted(vault, mediaId, jpeg.toByteArray())
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    fun delete(vault: Vault, mediaId: Long) {
        runCatching {
            val file = thumbnailFile(vault, mediaId)
            file.delete()
            File(file.parentFile, "${file.name}.tmp").delete()
        }
    }

    fun deleteAll(vault: Vault) {
        runCatching { keychain.thumbnailsFolder(vault).deleteRecursively() }
    }

    /** Decrypt a stored tile (small payload) using the process-wide keychain. */
    fun readThumbnailBytes(vault: Vault, file: File): ByteArray =
        keychain.decryptPortableBytes(vault, file)

    /**
     * Carry a thumbnail across vaults. The two vaults have different data keys, so the bytes are
     * decrypted with the source key and re-encrypted with the target key.
     */
    fun copy(sourceVault: Vault, targetVault: Vault, mediaId: Long): Boolean {
        val source = thumbnailFile(sourceVault, mediaId)
        if (!source.isFile) return false
        return runCatching {
            val plain = keychain.decryptPortableBytes(sourceVault, source)
            writeEncrypted(targetVault, mediaId, plain)
        }.getOrDefault(false)
    }

    // ---- decoding ----

    private fun decodeImage(uri: Uri): Bitmap? {
        // ImageDecoder (API 28+) applies EXIF orientation for us and can downsample during decode.
        runCatching {
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                if (info.size.width <= 0 || info.size.height <= 0) return@decodeBitmap
                decoder.setTargetSampleSize(
                    sampleSize(info.size.width, info.size.height, MAX_DIMENSION)
                )
                // Software bitmaps are required for JPEG compression and avoid hardware-only
                // buffers that some decoders refuse to read back.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }
        return decodeWithBitmapFactory(uri)
    }

    private fun decodeWithBitmapFactory(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // BitmapFactory ignores EXIF; rotate/flip explicitly so the tile matches the viewer.
        val orientation = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return applyOrientation(bitmap, orientation)
    }

    /** Downsampled decode from a re-openable decrypted stream, honouring EXIF orientation. */
    private fun decodeFromStream(open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = open()?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return applyOrientation(bitmap, readOrientation(open))
    }

    private fun decodeFromBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val orientation = runCatching {
            ByteArrayInputStream(bytes).use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return applyOrientation(bitmap, orientation)
    }

    private fun readOrientation(open: () -> InputStream?): Int = runCatching {
        open()?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private fun decodeVideoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val scaled = retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                MAX_DIMENSION,
                MAX_DIMENSION
            )
            scaled ?: retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )?.let { scaleDown(it) }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    // ---- encryption ----

    private fun writeEncrypted(vault: Vault, mediaId: Long, jpeg: ByteArray): Boolean {
        val target = thumbnailFile(vault, mediaId)
        return try {
            // The data key is created on first vault import; ensure it exists before encrypting.
            if (!keychain.isTransferable(vault)) {
                keychain.writeVaultInfo(vault, transferable = true)
            }
            target.parentFile?.mkdirs()
            // Unique scratch per write: the import freeze pass and a lazy grid freeze can target
            // the same item, and a shared `.tmp` name would let them interleave bytes.
            val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
            try {
                ByteArrayInputStream(jpeg).use { input ->
                    keychain.encryptPortableStream(vault, input, tmp, jpeg.size.toLong())
                }
                keychain.commitEncryptedFile(tmp, target)
            } finally {
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            // Leave any existing tile alone: an atomic publish either replaced it completely or
            // never touched it, so a failed regenerate must not delete a good thumbnail.
            printError(TAG, "Failed to write thumbnail for $mediaId", t)
            false
        }
    }

    companion object {
        private const val TAG = "vault.thumbs"
        private val MAX_DIMENSION = 512
        private val THUMB_QUALITY = 82

        @Volatile
        private var shared: VaultThumbnailStore? = null

        /** Process-wide instance; the grid calls this on every missing tile. */
        fun get(context: Context): VaultThumbnailStore =
            shared ?: synchronized(this) {
                shared ?: VaultThumbnailStore(context.applicationContext).also { shared = it }
            }

        /**
         * Resolve the thumbnail belonging to an encrypted vault file
         * (`<filesDir>/<uuid>/<id>.enc` -> `<filesDir>/.vault_thumbs/<uuid>/<id>.thumb`).
         * Returns null when the path is not a vault file or no thumbnail exists.
         */
        fun thumbnailForEncrypted(filesDir: File, encryptedFile: File): File? {
            val vaultId = encryptedFile.parentFile?.name ?: return null
            val mediaId = encryptedFile.nameWithoutExtension
            if (mediaId.isEmpty()) return null
            val thumb = File(File(File(filesDir, KeychainHolder.VAULT_THUMBS_DIR_NAME), vaultId), "$mediaId.thumb")
            return thumb.takeIf { it.isFile && it.length() > 0 }
        }

        /** Largest power-of-two sample that keeps the result at or above [target] px. */
        fun sampleSize(width: Int, height: Int, target: Int): Int {
            if (width <= 0 || height <= 0 || target <= 0) return 1
            var sample = 1
            var longest = max(width, height)
            while (longest / 2 >= target) {
                longest /= 2
                sample *= 2
            }
            return sample
        }
    }
}
