package com.nendo.argosy.data.upload.transfer

import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.nendo.argosy.domain.model.upload.HostKeyProblem
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.upload.CancellationCheck
import com.nendo.argosy.domain.upload.ProgressSink
import com.nendo.argosy.domain.upload.RemoteEntry
import com.nendo.argosy.domain.upload.RemoteStat
import com.nendo.argosy.domain.upload.RemoteTransferClient
import com.nendo.argosy.domain.upload.TransferException
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

sealed interface SftpAuth {
    class Password(val password: CharArray) : SftpAuth
    class PrivateKey(val pem: String, val passphrase: CharArray?) : SftpAuth
}

data class SftpEndpoint(
    val host: String,
    val port: Int,
    val username: String
)

class HostKeyRejectedException(
    val problem: HostKeyProblem,
    val presented: HostKeyFingerprint,
    val expected: HostKeyFingerprint?
) : IOException("Host key ${problem.name.lowercase()}")

object SshFingerprints {
    fun of(key: PublicKey): HostKeyFingerprint {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return HostKeyFingerprint(KeyType.fromKey(key).toString(), "SHA256:$encoded")
    }
}

/**
 * SFTP implementation of [RemoteTransferClient] over sshj. A host key is accepted only when it
 * matches [trustedKey]; an unknown or different key aborts the handshake before authentication
 * and surfaces as [HostKeyRejectedException] through [lastHostKeyRejection] and a
 * HOST_KEY_UNKNOWN or HOST_KEY_CHANGED [TransferException].
 */
class SftpTransferClient(
    private val endpoint: SftpEndpoint,
    private val auth: SftpAuth,
    private val trustedKey: HostKeyFingerprint?,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val socketTimeoutMillis: Int = DEFAULT_SOCKET_TIMEOUT_MS
) : RemoteTransferClient {

    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null

    @Volatile
    var lastHostKeyRejection: HostKeyRejectedException? = null
        private set

    @Volatile
    var presentedHostKey: HostKeyFingerprint? = null
        private set

    override fun connect() {
        if (sftp != null) return
        val client = SSHClient(DefaultConfig())
        client.connectTimeout = connectTimeoutMillis
        client.timeout = socketTimeoutMillis
        client.addHostKeyVerifier(Verifier())
        try {
            client.connect(endpoint.host, endpoint.port)
            when (auth) {
                is SftpAuth.Password -> client.authPassword(endpoint.username, auth.password.copyOf())
                is SftpAuth.PrivateKey -> {
                    val finder = auth.passphrase?.let { phrase -> StaticPasswordFinder(phrase) }
                    val keys = client.loadKeys(auth.pem, null, finder)
                    client.authPublickey(endpoint.username, keys)
                }
            }
            ssh = client
            sftp = client.newSFTPClient()
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw translate(e)
        }
    }

    override fun stat(path: String): RemoteStat? = guarded {
        requireSftp().statExistence(path)?.let { attrs ->
            RemoteStat(attrs.type == FileMode.Type.DIRECTORY, attrs.size)
        }
    }

    override fun list(directory: String): List<RemoteEntry> = guarded {
        requireSftp().ls(directory).map { info ->
            RemoteEntry(
                name = info.name,
                path = info.path,
                isDirectory = info.isDirectory,
                size = info.attributes.size
            )
        }
    }

    override fun makeDirectories(path: String) = guarded { requireSftp().mkdirs(path) }

    override fun upload(
        source: InputStream,
        sizeBytes: Long,
        destination: String,
        progress: ProgressSink,
        cancellation: CancellationCheck
    ) = guarded {
        val file: RemoteFile = requireSftp().open(
            destination,
            EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        )
        file.use { remote ->
            val out = remote.RemoteFileOutputStream(0, MAX_UNCONFIRMED_WRITES)
            out.use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var sent = 0L
                while (true) {
                    if (cancellation.isCancelled()) throw TransferException(UploadErrorKind.CANCELLED)
                    val read = source.read(buffer)
                    if (read < 0) break
                    stream.write(buffer, 0, read)
                    sent += read
                    progress.onBytes(sent)
                }
                if (sent != sizeBytes) {
                    throw TransferException(UploadErrorKind.SOURCE_CHANGED, "read $sent of $sizeBytes bytes")
                }
            }
        }
    }

    override fun rename(from: String, to: String) = guarded { requireSftp().rename(from, to) }

    override fun delete(path: String) = guarded { requireSftp().rm(path) }

    override fun availableBytes(path: String): Long? = null

    override fun close() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        sftp = null
        ssh = null
    }

    private fun requireSftp(): SFTPClient =
        sftp ?: throw TransferException(UploadErrorKind.CONNECTION_FAILED, "not connected")

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: TransferException) {
        throw e
    } catch (e: Exception) {
        throw translate(e)
    }

    private fun translate(e: Exception): TransferException {
        lastHostKeyRejection?.let { rejection ->
            val kind = when (rejection.problem) {
                HostKeyProblem.UNKNOWN -> UploadErrorKind.HOST_KEY_UNKNOWN
                HostKeyProblem.CHANGED -> UploadErrorKind.HOST_KEY_CHANGED
            }
            return TransferException(kind, rejection.presented.sha256, rejection)
        }
        return when (e) {
            is TransferException -> e
            is UserAuthException -> TransferException(UploadErrorKind.AUTH_FAILED, null, e)
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                TransferException(UploadErrorKind.CONNECTION_FAILED, e.javaClass.simpleName, e)
            is SocketTimeoutException -> TransferException(UploadErrorKind.TIMEOUT, null, e)
            is SFTPException -> when (e.statusCode) {
                Response.StatusCode.PERMISSION_DENIED -> TransferException(UploadErrorKind.PERMISSION_DENIED, null, e)
                Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH ->
                    TransferException(UploadErrorKind.DESTINATION_INVALID, null, e)
                Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED ->
                    TransferException(UploadErrorKind.NO_SPACE, null, e)
                Response.StatusCode.FILE_ALREADY_EXISTS -> TransferException(UploadErrorKind.REMOTE_EXISTS, null, e)
                else -> TransferException(UploadErrorKind.UNKNOWN, e.statusCode?.name, e)
            }
            is TransportException -> TransferException(UploadErrorKind.NETWORK_LOST, e.disconnectReason?.name, e)
            is IOException -> TransferException(UploadErrorKind.NETWORK_LOST, e.javaClass.simpleName, e)
            else -> TransferException(UploadErrorKind.UNKNOWN, e.javaClass.simpleName, e)
        }
    }

    private inner class Verifier : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val presented = SshFingerprints.of(key)
            presentedHostKey = presented
            return when {
                trustedKey == null -> {
                    lastHostKeyRejection = HostKeyRejectedException(HostKeyProblem.UNKNOWN, presented, null)
                    false
                }
                trustedKey == presented -> true
                else -> {
                    lastHostKeyRejection = HostKeyRejectedException(HostKeyProblem.CHANGED, presented, trustedKey)
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
            trustedKey?.let { listOf(it.keyType) } ?: emptyList()
    }

    private class StaticPasswordFinder(private val secret: CharArray) : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = secret.copyOf()

        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_SOCKET_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_UNCONFIRMED_WRITES = 16
    }
}
