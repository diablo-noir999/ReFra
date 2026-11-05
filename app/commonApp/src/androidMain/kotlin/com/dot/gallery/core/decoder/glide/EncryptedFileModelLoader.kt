package com.dot.gallery.core.decoder.glide

import android.content.Context
import android.net.Uri
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.dot.gallery.data.thumbnail.VaultThumbnailStore
import java.io.File
import java.security.MessageDigest

/**
 * Converts File or Uri models that point to encrypted vault items into an EncryptedMediaStream.
 * We register both File and Uri variants to intercept whichever form the UI supplies.
 */
class EncryptedFileModelLoader(
    private val context: Context
) : ModelLoader<File, EncryptedMediaStream> {

    override fun handles(model: File): Boolean = isEncryptedVaultFile(model)

    override fun buildLoadData(
        model: File,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<EncryptedMediaStream>? {
        if (!handles(model)) return null
        return ModelLoader.LoadData(
            EncryptedVaultKey(model.path),
            EncryptedVaultFetcher(model, context, options.get(VaultThumbnailOption))
        )
    }

    class Factory(
        private val context: Context
    ) : ModelLoaderFactory<File, EncryptedMediaStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<File, EncryptedMediaStream> =
            EncryptedFileModelLoader(context.applicationContext)

        override fun teardown() = Unit
    }
}

class EncryptedUriModelLoader(
    private val context: Context
) : ModelLoader<Uri, EncryptedMediaStream> {

    override fun handles(model: Uri): Boolean {
        val file = if (model.scheme == "file") model.path?.let { File(it) } else null
        return file?.let { isEncryptedVaultFile(it) } == true
    }

    override fun buildLoadData(
        model: Uri,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<EncryptedMediaStream>? {
        val file = model.path?.let { File(it) } ?: return null
        if (!handles(model)) return null
        return ModelLoader.LoadData(
            EncryptedVaultKey(file.path),
            EncryptedVaultFetcher(file, context, options.get(VaultThumbnailOption))
        )
    }

    class Factory(
        private val context: Context
    ) : ModelLoaderFactory<Uri, EncryptedMediaStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, EncryptedMediaStream> =
            EncryptedUriModelLoader(context.applicationContext)

        override fun teardown() = Unit
    }
}

/* Key + Fetcher */

data class EncryptedVaultKey(private val path: String) : com.bumptech.glide.load.Key {
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(path.toByteArray(Charsets.UTF_8))
    }
}

private class EncryptedVaultFetcher(
    private val file: File,
    private val context: Context,
    private val useThumbnail: Boolean
) : DataFetcher<EncryptedMediaStream> {

    private var stream: EncryptedMediaStream? = null
    private var cancelled = false

    override fun loadData(
        priority: Priority,
        callback: DataFetcher.DataCallback<in EncryptedMediaStream>
    ) {
        if (cancelled) return
        try {
            // Grid requests opt into the pre-generated encrypted tile: a few KB instead of a
            // whole-file decrypt. Missing/undecryptable tiles fall back to the original.
            val thumbnail = if (useThumbnail) {
                VaultThumbnailStore.thumbnailForEncrypted(context.filesDir, file)
                    ?.let { createVaultThumbnailSource(context, it) }
            } else null
            val result = thumbnail?.asMediaStream() ?: decryptVaultFile(file, context)
            thumbnail?.release()
            // Legacy vaults have no stored tile. Freeze one from the bytes we already decrypted
            // (only stills; video bytes would need a temp file and the import freeze covers them).
            if (thumbnail == null && useThumbnail && !result.isVideo) {
                runCatching {
                    val vault = vaultForEncryptedFile(file) ?: return@runCatching
                    val mediaId = file.nameWithoutExtension.toLongOrNull() ?: return@runCatching
                    VaultThumbnailStore.get(context).ensureFromBytes(vault, mediaId, result.bytes)
                }
            }
            stream = result
            callback.onDataReady(result)
        } catch (e: Exception) {
            callback.onLoadFailed(e)
        }
    }

    override fun cleanup() {
        stream = null
    }

    override fun cancel() {
        cancelled = true
    }

    override fun getDataClass(): Class<EncryptedMediaStream> = EncryptedMediaStream::class.java
    override fun getDataSource(): DataSource = DataSource.LOCAL
}