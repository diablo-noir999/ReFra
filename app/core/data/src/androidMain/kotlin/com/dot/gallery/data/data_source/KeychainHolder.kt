@file:Suppress("DEPRECATION")

package com.dot.gallery.data.data_source

import android.content.Context
import android.net.Uri
import android.security.keystore.UserNotAuthenticatedException
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
import androidx.security.crypto.MasterKey
import com.dot.gallery.domain.model.Vault
import com.dot.gallery.domain.model.VaultInfo
import com.dot.gallery.domain.util.toKotlinByteArray
import com.dot.gallery.core.logging.printError
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import com.dot.gallery.domain.model.EncryptedMedia
import com.dot.gallery.domain.uri.path
import com.dot.gallery.domain.util.migrate
import com.dot.gallery.domain.util.fromKotlinByteArray
import java.util.UUID


class KeychainHolder @Inject constructor(
    @PublishedApi internal val context: Context
) {

    val filesDir: File = context.filesDir

    fun vaultFolder(vault: Vault) = File(filesDir, vault.uuid.toString())
    private fun vaultInfoFile(vault: Vault) = File(vaultFolder(vault), VAULT_INFO_FILE_NAME)
    fun Vault.mediaFile(mediaId: Long) = File(vaultFolder(this), "$mediaId.enc")

    /**
     * Hidden store holding pre-generated, encrypted thumbnails, one folder per vault.
     * It sits next to the vault folders but is never listed as vault content: `getEncryptedMedia`
     * only walks [vaultFolder], and the dot-prefix keeps it out of user-visible file browsers.
     */
    fun thumbnailsFolder(vault: Vault) = File(File(filesDir, VAULT_THUMBS_DIR_NAME), vault.uuid.toString())
    fun Vault.thumbnailFile(mediaId: Long) = File(thumbnailsFolder(this), "$mediaId.thumb")

    @PublishedApi internal val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    /**
     * Legacy write: always device-locked (non-transferable). Kept for backward compatibility.
     */
    fun writeVaultInfo(vault: Vault, onSuccess: () -> Unit = {}, onFailed: (reason: String) -> Unit = {}) {
        try {
            val vaultFolder = File(filesDir, vault.uuid.toString())
            if (!vaultFolder.exists()) {
                vaultFolder.mkdirs()
            }

            vaultInfoFile(vault).let { infoFile ->
                val tmp = tempFileFor(infoFile).apply { delete() }
                try {
                    tmp.encryptKotlin(vault)
                    commitEncryptedFile(tmp, infoFile)
                } catch (t: Throwable) {
                    tmp.delete()
                    throw t
                }
                onSuccess()
            }
        } catch (e: Exception) {
            printError(TAG, "Failed to write vault info for ${vault.uuid}", e)
            onFailed(e.message.toString())
        }
    }

    /**
     * New portable vault initialization.
     * When transferable=true we generate a random 32-byte Data Key (DK), wrap it with EncryptedFile
     * and store plaintext metadata (VaultInfo) containing dkHash for integrity.
     */
    fun writeVaultInfo(
        vault: Vault,
        transferable: Boolean,
        force: Boolean = false,
        onSuccess: () -> Unit = {},
        onFailed: (reason: String) -> Unit = {}
    ) {
        try {
            val folder = vaultFolder(vault)
            if (!folder.exists()) folder.mkdirs()

            if (transferable) {
                // Two vault operations can race here (bulk worker + grid thumbnail freeze).
                // Without a process-wide lock both could generate a different DK and the last
                // writer would silently strand files encrypted with the losing key.
                synchronized(DATA_KEY_LOCK) {
                    val dkFile = dataKeyFile(vault)
                    val existingKey = if (force) null else readDataKeyFile(vault)
                    val dk = existingKey ?: generateDataKey().also { key ->
                        // Store encrypted with MasterKey for local-at-rest protection, atomically:
                        // a torn key file would make the whole vault undecryptable.
                        val tmp = tempFileFor(dkFile).apply { delete() }
                        try {
                            EncryptedFile.Builder(
                                context,
                                tmp,
                                masterKey,
                                AES256_GCM_HKDF_4KB
                            ).build().openFileOutput().use { it.write(key) }
                            commitEncryptedFile(tmp, dkFile)
                        } catch (t: Throwable) {
                            tmp.delete()
                            throw t
                        }
                    }
                    // Always (re)write the metadata. If a previous attempt died between the key and
                    // the metadata, this repairs the vault instead of leaving it permanently
                    // "non-transferable" with an existing key.
                    writePortableMetadata(vault, dk)
                }
            } else {
                // Non-transferable path: keep previous encrypted info object
                writeVaultInfo(vault)
            }
            onSuccess()
        } catch (e: Exception) {
            printError(TAG, "Failed to write vault info for ${vault.uuid}", e)
            onFailed(e.message.toString())
        }
    }

    fun deleteVault(vault: Vault, onSuccess: () -> Unit, onFailed: (reason: String) -> Unit) {
        try {
            val vaultFolder = vaultFolder(vault)
            if (vaultFolder.exists()) {
                vaultFolder.deleteRecursively()
            }
            // Thumbnails carry the same data key; leaving them behind would outlive the vault.
            runCatching { thumbnailsFolder(vault).deleteRecursively() }
            onSuccess()
        } catch (e: Exception) {
            printError(TAG, "Failed to delete vault ${vault.uuid}", e)
            onFailed(e.message.toString())
        }
    }

    fun checkVaultFolder(vault: Vault) {
        val mainFolder = File(filesDir, vault.uuid.toString())
        if (!mainFolder.exists()) {
            mainFolder.mkdirs()
            writeVaultInfo(vault)
        }
        maybeCleanupInterruptedEncrypts(vault)
    }

    // ---- crash-safe publish ----

    /** Temp path used while encrypting. Same directory, so publishing it is an atomic move. */
    fun tempFileFor(target: File): File = File(target.parentFile, "${target.name}.tmp")

    private fun backupFileFor(target: File): File = File(target.parentFile, "${target.name}.bak")

    /**
     * Publish a fully written (and fsynced) [tmp] file as [target].
     *
     * Never writes the final path in place: a crash mid-encryption leaves only a `.tmp` file,
     * while the previous [target] — if any — is still intact. On the happy path this is a single
     * atomic rename. If the filesystem refuses an atomic move, the old file is moved to `.bak`
     * first and restored when anything fails, so no completed vault item is ever destroyed by a
     * failed re-encrypt.
     */
    @Throws(IOException::class)
    fun commitEncryptedFile(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
            return
        } catch (_: Throwable) {
            // Fall through to the conservative copy path below.
        }

        val backup = backupFileFor(target)
        val hadTarget = target.exists()
        try {
            if (hadTarget && !target.renameTo(backup)) {
                throw IOException("Cannot move aside ${target.name}")
            }
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            backup.delete()
        } catch (t: Throwable) {
            target.delete()
            if (hadTarget && backup.exists()) {
                runCatching { backup.renameTo(target) }
            }
            throw t
        }
    }

    /**
     * Recover from encrypts interrupted by process death: restore an orphan `.bak` when its
     * target vanished, and delete stale `.tmp`/`.bak` leftovers. Runs at most once per hour.
     */
    private fun maybeCleanupInterruptedEncrypts(vault: Vault) {
        val now = System.currentTimeMillis()
        val previous = LAST_CLEANUP_AT.get()
        if (now - previous < CLEANUP_INTERVAL_MS) return
        if (!LAST_CLEANUP_AT.compareAndSet(previous, now)) return
        cleanupInterruptedEncrypts(vault)
    }

    fun cleanupInterruptedEncrypts(vault: Vault) {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
        val directories = listOf(vaultFolder(vault), thumbnailsFolder(vault))
        for (directory in directories) {
            val files = directory.listFiles() ?: continue
            for (file in files) {
                if (file.lastModified() >= cutoff) continue
                when {
                    file.name.endsWith(".bak") -> {
                        val baseName = file.name.removeSuffix(".bak")
                        val target = File(directory, baseName)
                        val interruptedTmp = File(directory, "$baseName.tmp")
                        val mustRestore = !target.exists() || interruptedTmp.exists()
                        if (mustRestore) {
                            // The publish renamed the old file aside and died before the new
                            // target was complete: put the only known-good copy back.
                            if (target.exists()) target.delete()
                            file.renameTo(target)
                        } else {
                            // Target was published completely; the backup is redundant.
                            file.delete()
                        }
                    }
                    file.name.endsWith(".tmp") -> file.delete()
                }
            }
        }
    }

    /** Determine if vault is portable by inspecting metadata file (plaintext JSON). */
    fun isTransferable(vault: Vault): Boolean = try {
        val info = readVaultInfo(vault) ?: return false
        info.transferable
    } catch (_: Exception) { false }

    fun readVaultInfo(vault: Vault): VaultInfo? {
        val infoFile = vaultInfoFile(vault)
        if (!infoFile.exists()) return null
        return try {
            // Try plaintext JSON first (portable). If that fails fall back to legacy encrypted
            val text = infoFile.readText()
            fromKotlinByteArray<VaultInfo>(text.toByteArray())
        } catch (_: Exception) {
            // Legacy path: encrypted Vault object (ignore for portable metadata)
            null
        }
    }

    private fun dataKeyFile(vault: Vault) = File(vaultFolder(vault), DATA_KEY_FILE_NAME)

    private fun generateDataKey(): ByteArray = ByteArray(32).apply { SecureRandom().nextBytes(this) }

    private fun ByteArray.sha256Base64(): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(this))

    /** Atomically (re)write the plaintext VaultInfo describing the wrapped key. */
    private fun writePortableMetadata(vault: Vault, dk: ByteArray) {
        val infoFile = vaultInfoFile(vault)
        val infoTmp = tempFileFor(infoFile).apply { delete() }
        val meta = VaultInfo(
            version = 1,
            transferable = true,
            dkHash = dk.sha256Base64(),
            uuid = vault.uuid.toString()
        )
        try {
            infoTmp.writeText(meta.toKotlinByteArray().decodeToString())
            commitEncryptedFile(infoTmp, infoFile)
        } catch (t: Throwable) {
            infoTmp.delete()
            throw t
        }
    }

    /** Load raw DK (only for transferable vault). */
    fun loadDataKey(vault: Vault): ByteArray? {
        if (!isTransferable(vault)) return null
        return readDataKeyFile(vault)
    }

    /** Read the wrapped DK file regardless of metadata state (used for crash repair). */
    private fun readDataKeyFile(vault: Vault): ByteArray? {
        val file = dataKeyFile(vault)
        if (!file.exists()) return null
        return runCatching {
            EncryptedFile.Builder(
                context,
                file,
                masterKey,
                AES256_GCM_HKDF_4KB
            ).build().openFileInput().use { it.readBytes() }
        }.getOrNull()
    }

    /** Export base64 DK for user backup (only if transferable). */
    fun exportDataKeyBase64(vault: Vault): String? = loadDataKey(vault)?.let {
        Base64.getEncoder().encodeToString(it)
    }

    // ---- Portable content encryption helpers ----
    // V1 layout: MAGIC_V1(6) | NONCE(12) | CIPHERTEXT+TAG (single GCM, in-memory)
    // V2 layout: MAGIC_V2(6) | BASE_NONCE(12) | CHUNK_SIZE(4) | [LEN(4)+CHUNK_CIPHERTEXT]*
    private val MAGIC = "VLTv1".toByteArray() // 5 bytes + sentinel
    private val MAGIC_V2 = "VLTv2".toByteArray()
    private val MAGIC_PAD = 6 // ensure fixed length (add 1 padding byte)

    /**
     * 4 MiB per GCM chunk. The chunk size is written into the file header and each chunk carries
     * its own length, so this only affects newly written files — existing vaults keep decrypting
     * with whatever size they were written at. Larger chunks cut per-chunk overhead (cipher init,
     * header writes, stream round-trips) several-fold during bulk import.
     */
    private val STREAM_CHUNK_SIZE = 4 * 1024 * 1024
    private const val MIN_CHUNK_SIZE = 64 * 1024
    private const val MAX_CHUNK_SIZE = 64 * 1024 * 1024 // reject corrupt/hostile length prefixes

    fun encryptPortableContent(vault: Vault, plaintext: ByteArray): ByteArray {
        val dk = loadDataKey(vault) ?: error("Data key missing for portable vault")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        cipher.init(Cipher.ENCRYPT_MODE, dk.toAesKey(), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plaintext)
        return ByteArray(MAGIC_PAD + nonce.size + ct.size).apply {
            System.arraycopy(MAGIC, 0, this, 0, MAGIC.size)
            this[MAGIC.size] = 0 // padding byte
            System.arraycopy(nonce, 0, this, MAGIC_PAD, nonce.size)
            System.arraycopy(ct, 0, this, MAGIC_PAD + nonce.size, ct.size)
        }
    }

    /**
     * Chunked streaming encryption (VLTv2). Each chunk is independently GCM-encrypted
     * so the cipher never accumulates more than STREAM_CHUNK_SIZE in memory.
     */
    fun encryptPortableStream(
        vault: Vault,
        input: InputStream,
        outputFile: File,
        inputSize: Long? = null
    ) {
        val dk = loadDataKey(vault) ?: error("Data key missing for portable vault")
        val key = dk.toAesKey()
        val baseNonce = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        // One Cipher instance re-initialised per chunk: creating a provider-backed Cipher for
        // every chunk was measurable overhead on multi-GB imports.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Size the read buffer to the payload when the caller knows it. Thumbnails are ~50 KB,
        // and allocating a 4 MB buffer per thumbnail during a 3k-item import is pure GC churn.
        // The format carries each chunk's length, so chunks may be smaller than the nominal
        // STREAM_CHUNK_SIZE written in the header.
        val chunkBudget = when {
            inputSize == null || inputSize <= 0L -> STREAM_CHUNK_SIZE
            else -> inputSize.coerceIn(MIN_CHUNK_SIZE.toLong(), STREAM_CHUNK_SIZE.toLong()).toInt()
        }

        // Write explicitly so we can fsync before the caller publishes the file. Without the
        // sync, a rename can be durable while the payload is not, leaving a zero-length vault
        // item after a power loss.
        FileOutputStream(outputFile).use { fos ->
            val dos = DataOutputStream(BufferedOutputStream(fos, 64 * 1024))
            // Header
            dos.write(MAGIC_V2)
            dos.write(ByteArray(MAGIC_PAD - MAGIC_V2.size)) // padding to 6 bytes
            dos.write(baseNonce)
            dos.writeInt(STREAM_CHUNK_SIZE)

            val readBuf = ByteArray(chunkBudget)
            var chunkIndex = 0L
            while (true) {
                val bytesRead = readFully(input, readBuf)
                if (bytesRead == 0) break

                val chunkNonce = deriveChunkNonce(baseNonce, chunkIndex++)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, chunkNonce))
                val ct = cipher.doFinal(readBuf, 0, bytesRead)
                dos.writeInt(ct.size)
                dos.write(ct)
            }
            dos.flush()
            fos.fd.sync()
        }
    }

    fun decryptPortableContent(vault: Vault, bytes: ByteArray): ByteArray {
        if (!startsWithMagic(bytes)) error("Not portable content")
        val dk = loadDataKey(vault) ?: error("Data key missing")
        val nonce = bytes.copyOfRange(MAGIC_PAD, MAGIC_PAD + 12)
        val ct = bytes.copyOfRange(MAGIC_PAD + 12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, dk.toAesKey(), GCMParameterSpec(128, nonce))
        return cipher.doFinal(ct)
    }

    /** Decrypt a portable (VLTv1/VLTv2) file fully into memory. Intended for small payloads. */
    fun decryptPortableBytes(vault: Vault, inputFile: File): ByteArray {
        val out = ByteArrayOutputStream(
            inputFile.length().coerceAtMost(8L * 1024 * 1024).toInt().coerceAtLeast(1024)
        )
        decryptPortableStream(vault, inputFile, out)
        return out.toByteArray()
    }

    /**
     * Streaming decryption that auto-detects VLTv1 (single GCM) vs VLTv2 (chunked GCM).
     */
    fun decryptPortableStream(vault: Vault, inputFile: File, output: OutputStream) {
        val dk = loadDataKey(vault) ?: error("Data key missing")
        val key = dk.toAesKey()
        inputFile.inputStream().buffered().use { input ->
            val dis = DataInputStream(input)
            val header = ByteArray(MAGIC_PAD)
            dis.readFully(header)

            if (startsWithMagicV2(header)) {
                // VLTv2 chunked decryption
                val baseNonce = ByteArray(12)
                dis.readFully(baseNonce)
                dis.readInt() // chunk size hint; every chunk carries its own length below

                // Allocate from the first chunk's real length instead of the 4 MiB nominal size,
                // so decrypting a 50 KB thumbnail does not reserve 4 MiB.
                var ct = ByteArray(0)
                var chunkIndex = 0L
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                while (true) {
                    val ctLen = try {
                        dis.readInt()
                    } catch (_: EOFException) {
                        break
                    }
                    require(ctLen in 1..MAX_CHUNK_SIZE) { "Invalid vault chunk length: $ctLen" }
                    if (ct.size < ctLen) ct = ByteArray(ctLen)
                    dis.readFully(ct, 0, ctLen)
                    val chunkNonce = deriveChunkNonce(baseNonce, chunkIndex++)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, chunkNonce))
                    // doFinal(byte[], int, int) truncates to ctLen without an intermediate copy.
                    output.write(cipher.doFinal(ct, 0, ctLen))
                }
            } else if (startsWithMagic(header)) {
                // VLTv1 single-GCM (legacy streaming — works for small files)
                val nonce = ByteArray(12)
                dis.readFully(nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var len: Int
                while (input.read(buffer).also { len = it } != -1) {
                    val decrypted = cipher.update(buffer, 0, len)
                    if (decrypted != null) output.write(decrypted)
                }
                val finalBlock = cipher.doFinal()
                if (finalBlock != null && finalBlock.isNotEmpty()) output.write(finalBlock)
            } else {
                error("Not portable content")
            }
        }
    }

    fun isPortableFile(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_PAD) return false
        val header = ByteArray(MAGIC_PAD)
        file.inputStream().use { it.read(header) }
        return startsWithMagic(header) || startsWithMagicV2(header)
    }

    /**
     * Attempt portable decryption; if magic header absent falls back to returning original bytes.
     */
    fun decryptPortableIfNeeded(vault: Vault, bytes: ByteArray): ByteArray =
        if (startsWithMagic(bytes)) decryptPortableContent(vault, bytes) else bytes

    /**
     * Import a portable vault by injecting a provided Base64 data key.
     * If vault folder exists and already has key, it's left unchanged unless force=true.
     */
    fun importPortableVault(vault: Vault, base64Key: String, force: Boolean = false): Boolean {
        return try {
            val dk = Base64.getDecoder().decode(base64Key)
            require(dk.size == 32) { "Invalid key length" }
            val folder = vaultFolder(vault)
            if (!folder.exists()) folder.mkdirs()
            val dkFile = dataKeyFile(vault)
            synchronized(DATA_KEY_LOCK) {
                if (dkFile.exists() && !force) {
                    // Repair metadata if a previous import died between the key and the metadata.
                    readDataKeyFile(vault)?.let { writePortableMetadata(vault, it) }
                    return true
                }
                // Write encrypted DK file atomically: a torn key file bricks the vault.
                val dkTmp = tempFileFor(dkFile).apply { delete() }
                try {
                    EncryptedFile.Builder(
                        context,
                        dkTmp,
                        masterKey,
                        AES256_GCM_HKDF_4KB
                    ).build().openFileOutput().use { it.write(dk) }
                    commitEncryptedFile(dkTmp, dkFile)
                } catch (t: Throwable) {
                    dkTmp.delete()
                    throw t
                }
                writePortableMetadata(vault, dk)
            }
            true
        } catch (e: Exception) {
            printError(TAG, "Failed to import portable vault ${vault.uuid}", e)
            false
        }
    }

    /**
     * Migrate an existing non-transferable (legacy) vault to portable format.
     * Creates data key and re-encrypts each legacy EncryptedMedia file into portable binary format.
     */
    fun migrateVaultToPortable(vault: Vault, onProgress: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        return try {
            if (isTransferable(vault)) return true // already portable
            // Initialize transferable vault (generates DK & metadata)
            writeVaultInfo(vault, transferable = true)
            val files = vaultFolder(vault).listFiles { f -> f.name.endsWith(".enc") } ?: emptyArray()
            var index = 0
            files.forEach { f ->
                try {
                    val encrypted = f.decryptKotlin<EncryptedMedia>()
                    val portableBytes = encryptPortableContent(vault, encrypted.bytes)
                    // Write to a scratch file and publish atomically; an interrupted migration
                    // must leave the original legacy file readable, never a half-written one.
                    val tmp = tempFileFor(f).apply { delete() }
                    try {
                        tmp.writeBytes(portableBytes)
                        commitEncryptedFile(tmp, f)
                    } catch (t: Throwable) {
                        tmp.delete()
                        throw t
                    }
                } catch (e: Throwable) {
                    // leave file as-is if failure; could log
                    printError(TAG, "Failed to migrate file ${f.name} to portable format", e)
                }
                index++
                onProgress(index, files.size)
            }
            true
        } catch (e: Exception) {
            printError(TAG, "Failed to migrate vault ${vault.uuid} to portable format", e)
            false
        }
    }

    fun startsWithMagic(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC_PAD) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    fun startsWithMagicV2(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC_PAD) return false
        for (i in MAGIC_V2.indices) if (bytes[i] != MAGIC_V2[i]) return false
        return true
    }

    fun isPortableV2File(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_PAD) return false
        val header = ByteArray(MAGIC_PAD)
        file.inputStream().use { it.read(header) }
        return startsWithMagicV2(header)
    }

    /** XOR chunk index into the last 8 bytes of the base nonce to derive a unique per-chunk nonce. */
    private fun deriveChunkNonce(baseNonce: ByteArray, chunkIndex: Long): ByteArray {
        val nonce = baseNonce.copyOf()
        for (i in 0 until 8) {
            nonce[nonce.size - 1 - i] = (nonce[nonce.size - 1 - i].toInt() xor ((chunkIndex shr (i * 8)) and 0xFF).toInt()).toByte()
        }
        return nonce
    }

    /** Read up to buf.size bytes from input, returning actual count (0 at EOF). */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n == -1) break
            offset += n
        }
        return offset
    }

    private fun ByteArray.toAesKey(): SecretKey = SecretKeySpec(this, "AES")

    @Throws(GeneralSecurityException::class, IOException::class, FileNotFoundException::class, UserNotAuthenticatedException::class)
    inline fun <reified T> File.decryptKotlin(): T = EncryptedFile.Builder(
        context,
        this,
        masterKey,
        AES256_GCM_HKDF_4KB
    ).build().openFileInput().use {
        fromKotlinByteArray(it.readBytes())
    }
/*
    @Throws(GeneralSecurityException::class, IOException::class, FileNotFoundException::class, UserNotAuthenticatedException::class)
    fun <T : Serializable> File.decrypt(): T = EncryptedFile.Builder(
        context,
        this,
        masterKey,
        AES256_GCM_HKDF_4KB
    ).build().openFileInput().use {
        fromByteArray(it.readBytes())
    }*/

    @Throws(GeneralSecurityException::class, IOException::class, FileNotFoundException::class, UserNotAuthenticatedException::class)
    inline fun <reified T> File.encryptKotlin(data: T) {
        EncryptedFile.Builder(
            context,
            this,
            masterKey,
            AES256_GCM_HKDF_4KB
        ).build().openFileOutput().use {
            it.write(data.toKotlinByteArray())
        }
    }

    @Throws(IOException::class)
    fun getBytes(uri: Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val byteBuffer = ByteArrayOutputStream()
            val bufferSize = 1024
            val buffer = ByteArray(bufferSize)

            var len: Int
            while (inputStream.read(buffer).also { len = it } != -1) {
                byteBuffer.write(buffer, 0, len)
            }
            byteBuffer.toByteArray()
        }

    /**
     * Unified vault file decryption that handles both portable (VLTv1) and legacy (EncryptedFile) formats.
     * Returns raw decrypted bytes and the mime type.
     */
    /**
     * Decrypt a VLTv2 file to a temporary file (streaming, constant memory).
     * Returns the temp file and sniffed MIME type.
     */
    fun decryptToTempFile(file: File, cacheDir: File? = null): DecryptedVaultMedia {
        val vaultUuidStr = file.parentFile?.name ?: error("Cannot determine vault Uuid from path")
        val vault = Vault(uuid = Uuid.parse(vaultUuidStr), name = "")
        val tmpDir = cacheDir ?: context.cacheDir
        val tempFile = File.createTempFile("vault_dec_", ".tmp", tmpDir)
        FileOutputStream(tempFile).use { out ->
            decryptPortableStream(vault, file, out)
        }
        // Sniff MIME from first 12 bytes of decrypted content
        val header = ByteArray(12)
        tempFile.inputStream().use { it.read(header) }
        val mime = sniffMimeType(header)
        return DecryptedVaultMedia(bytes = null, mimeType = mime, tempFile = tempFile)
    }

    fun decryptVaultMedia(file: File): DecryptedVaultMedia {
        val vaultUuidStr = file.parentFile?.name ?: error("Cannot determine vault Uuid from path")
        val vault = Vault(uuid = Uuid.parse(vaultUuidStr), name = "")

        if (isPortableV2File(file)) {
            // VLTv2: stream-decrypt to temp file (constant memory)
            return decryptToTempFile(file)
        }
        if (isPortableFile(file)) {
            val encBytes = file.readBytes()
            val plainBytes = decryptPortableContent(vault, encBytes)
            val mime = sniffMimeType(plainBytes)
            return DecryptedVaultMedia(plainBytes, mime)
        }
        val enc = file.decryptKotlin<EncryptedMedia>()
        return DecryptedVaultMedia(enc.bytes, enc.mimeType)
    }

    companion object {
        private const val TAG = "vault.keychain"
        const val VAULT_INFO_FILE_NAME = "info.vault"
        const val DATA_KEY_FILE_NAME = "vault.key.enc"
        const val VAULT_THUMBS_DIR_NAME = ".vault_thumbs"

        /** Stale encrypted-write scratch files are only removed after this long (ms). */
        private const val STALE_TEMP_MS = 6 * 60 * 60 * 1000L
        private const val CLEANUP_INTERVAL_MS = 60 * 60 * 1000L
        private val LAST_CLEANUP_AT = java.util.concurrent.atomic.AtomicLong(0L)

        /** Serialises data-key creation across all KeychainHolder instances in the process. */
        private val DATA_KEY_LOCK = Any()

        fun sniffMimeType(bytes: ByteArray): String {
            if (bytes.size < 12) return "application/octet-stream"
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return "image/png"
            if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return "image/gif"
            if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
            ) return "image/webp"
            if (bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() && bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()) {
                val brand = String(bytes, 8, 4)
                if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1")) return "image/heif"
                return "video/mp4"
            }
            if (bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()) return "image/bmp"
            return "application/octet-stream"
        }
    }
}