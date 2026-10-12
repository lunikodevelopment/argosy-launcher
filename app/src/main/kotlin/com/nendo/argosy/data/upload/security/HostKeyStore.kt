package com.nendo.argosy.data.upload.security

import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File

@JsonClass(generateAdapter = true)
data class TrustedHostKey(
    val host: String,
    val port: Int,
    val keyType: String,
    val sha256: String,
    val trustedAtMillis: Long
)

@JsonClass(generateAdapter = true)
data class TrustedHostKeyDocument(
    val keys: List<TrustedHostKey>
)

/**
 * SFTP host keys the user confirmed, one per host and port. A key enters only through [trust],
 * called after the user compared the fingerprint; nothing here accepts a key on first sight.
 */
class HostKeyStore(
    private val file: File,
    moshi: Moshi,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val adapter = moshi.adapter(TrustedHostKeyDocument::class.java)

    @Synchronized
    fun trusted(host: String, port: Int): HostKeyFingerprint? =
        read().firstOrNull { it.matches(host, port) }?.let { HostKeyFingerprint(it.keyType, it.sha256) }

    @Synchronized
    fun trust(host: String, port: Int, fingerprint: HostKeyFingerprint) {
        val entry = TrustedHostKey(normalize(host), port, fingerprint.keyType, fingerprint.sha256, clock())
        write(read().filterNot { it.matches(host, port) } + entry)
    }

    @Synchronized
    fun forget(host: String, port: Int) {
        write(read().filterNot { it.matches(host, port) })
    }

    private fun TrustedHostKey.matches(host: String, port: Int) = this.host == normalize(host) && this.port == port

    private fun normalize(host: String) = host.trim().lowercase()

    private fun read(): List<TrustedHostKey> {
        if (!file.exists()) return emptyList()
        return runCatching { adapter.fromJson(file.readText())?.keys }.getOrNull().orEmpty()
    }

    private fun write(keys: List<TrustedHostKey>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(adapter.toJson(TrustedHostKeyDocument(keys)))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) error("could not replace ${file.name}")
        }
    }
}
