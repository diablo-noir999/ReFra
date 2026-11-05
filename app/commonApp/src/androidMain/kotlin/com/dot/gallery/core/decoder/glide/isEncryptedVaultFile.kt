package com.dot.gallery.core.decoder.glide

import android.content.Context
import com.dot.gallery.BuildConfig
import com.dot.gallery.core.decryption.VaultDecryptStore
import com.dot.gallery.data.data_source.KeychainHolder
import com.dot.gallery.domain.model.Vault
import java.io.File
import kotlin.uuid.Uuid

fun isEncryptedVaultFile(file: File): Boolean =
    file.path.contains(BuildConfig.APPLICATION_ID) && file.extension == "enc"

/**
 * Reconstruct a lightweight [Vault] from an encrypted item's parent folder (vault UUID).
 * The data key is read from disk by UUID, so an empty name is sufficient.
 */
internal fun vaultForEncryptedFile(file: File): Vault? = runCatching {
    val vaultId = file.parentFile?.name ?: return null
    Vault(uuid = Uuid.parse(vaultId), name = "")
}.getOrNull()

/**
 * Decrypts a vault file and returns bytes + mime type.
 * Handles both portable (VLTv1) and legacy (EncryptedFile) formats.
 * Reads from the canonical shared decrypted file managed by [VaultDecryptStore] (#1282).
 */
fun decryptVaultFile(file: File, context: Context): EncryptedMediaStream {
    val handle = VaultDecryptStore.acquire(KeychainHolder(context), file)
    return try {
        EncryptedMediaStream(
            bytes = handle.file.inputStream().use { it.readBytes() },
            mimeType = handle.mimeType,
            isVideo = handle.mimeType.startsWith("video")
        )
    } finally {
        handle.release()
    }
}