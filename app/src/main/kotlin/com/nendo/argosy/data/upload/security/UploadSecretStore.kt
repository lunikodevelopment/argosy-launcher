package com.nendo.argosy.data.upload.security

import java.io.File

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

enum class UploadSecretKind(val fileSuffix: String) {
    PASSWORD("pw"),
    PRIVATE_KEY("key"),
    KEY_PASSPHRASE("kp"),
    SCAN_TOKEN("scan")
}

/**
 * Upload credentials, one sealed file per profile and kind under a private directory. Kept apart from
 * the RomM API session and never logged; a value that fails to decrypt reads as absent, which sends
 * the user back to re-enter it.
 */
class UploadSecretStore(
    private val directory: File,
    private val cipher: SecretCipher
) {
    @Synchronized
    fun put(profileId: String, kind: UploadSecretKind, value: CharArray) {
        directory.mkdirs()
        val plain = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(value)).let { buffer ->
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        try {
            val target = fileFor(profileId, kind)
            val temp = File(directory, "${target.name}.tmp")
            temp.writeBytes(cipher.encrypt(plain))
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) error("could not store secret")
            }
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized
    fun get(profileId: String, kind: UploadSecretKind): CharArray? {
        val file = fileFor(profileId, kind)
        if (!file.exists()) return null
        val plain = runCatching { cipher.decrypt(file.readBytes()) }.getOrNull() ?: return null
        return try {
            val chars = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(plain))
            CharArray(chars.remaining()).also { chars.get(it) }
        } finally {
            plain.fill(0)
        }
    }

    fun has(profileId: String, kind: UploadSecretKind): Boolean = fileFor(profileId, kind).exists()

    @Synchronized
    fun remove(profileId: String, kind: UploadSecretKind) {
        fileFor(profileId, kind).delete()
    }

    @Synchronized
    fun removeProfile(profileId: String) {
        UploadSecretKind.entries.forEach { fileFor(profileId, it).delete() }
    }

    private fun fileFor(profileId: String, kind: UploadSecretKind): File {
        require(profileId.isNotEmpty() && profileId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "invalid profile id"
        }
        return File(directory, "$profileId.${kind.fileSuffix}")
    }
}
