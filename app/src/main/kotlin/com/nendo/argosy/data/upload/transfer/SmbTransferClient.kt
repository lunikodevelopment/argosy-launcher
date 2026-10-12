package com.nendo.argosy.data.upload.transfer

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.upload.CancellationCheck
import com.nendo.argosy.domain.upload.ProgressSink
import com.nendo.argosy.domain.upload.RemoteEntry
import com.nendo.argosy.domain.upload.RemoteStat
import com.nendo.argosy.domain.upload.RemoteTransferClient
import com.nendo.argosy.domain.upload.TransferException
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbEndpoint(
    val host: String,
    val port: Int,
    val share: String,
    val username: String,
    val domain: String?,
    val requireEncryption: Boolean
)

/**
 * SMB implementation of [RemoteTransferClient] over smbj, limited to the SMB 2.0.2 through 3.1.1
 * dialects with message signing required and optional encryption.
 */
class SmbTransferClient(
    private val endpoint: SmbEndpoint,
    private val password: CharArray,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) : RemoteTransferClient {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    override fun connect() {
        if (share != null) return
        val config = SmbConfig.builder()
            .withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_2_0_2
            )
            .withSigningRequired(true)
            .withEncryptData(endpoint.requireEncryption)
            .withTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .withSoTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val smb = SMBClient(config)
        try {
            val conn = smb.connect(endpoint.host, endpoint.port)
            val auth = AuthenticationContext(endpoint.username, password.copyOf(), endpoint.domain.orEmpty())
            val sess = conn.authenticate(auth)
            val disk = sess.connectShare(endpoint.share) as? DiskShare
                ?: throw TransferException(UploadErrorKind.DESTINATION_INVALID, "share is not a disk share")
            client = smb
            connection = conn
            session = sess
            share = disk
        } catch (e: Exception) {
            runCatching { smb.close() }
            throw translate(e)
        }
    }

    override fun stat(path: String): RemoteStat? = guarded {
        val disk = requireShare()
        val wire = wirePath(path)
        when {
            wire.isEmpty() -> RemoteStat(true, 0)
            disk.folderExists(wire) -> RemoteStat(true, 0)
            disk.fileExists(wire) -> {
                val info = disk.getFileInformation(wire, FileStandardInformation::class.java)
                RemoteStat(false, info.endOfFile)
            }
            else -> null
        }
    }

    override fun list(directory: String): List<RemoteEntry> = guarded {
        requireShare().list(wirePath(directory))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { info ->
                val isDirectory = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                RemoteEntry(
                    name = info.fileName,
                    path = if (directory.isEmpty()) info.fileName else "$directory/${info.fileName}",
                    isDirectory = isDirectory,
                    size = info.endOfFile
                )
            }
    }

    override fun makeDirectories(path: String) = guarded {
        val disk = requireShare()
        var current = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            current = if (current.isEmpty()) segment else "$current\\$segment"
            if (!disk.folderExists(current)) disk.mkdir(current)
        }
    }

    override fun upload(
        source: InputStream,
        sizeBytes: Long,
        destination: String,
        progress: ProgressSink,
        cancellation: CancellationCheck
    ) = guarded {
        val file = requireShare().openFile(
            wirePath(destination),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        )
        file.use { remote ->
            remote.outputStream.use { stream ->
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

    override fun rename(from: String, to: String) = guarded {
        val file = requireShare().openFile(
            wirePath(from),
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        )
        file.use { it.rename(wirePath(to), false) }
    }

    override fun delete(path: String) = guarded { requireShare().rm(wirePath(path)) }

    override fun availableBytes(path: String): Long? = guarded {
        runCatching { requireShare().shareInformation.freeSpace }.getOrNull()
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    private fun wirePath(path: String): String = path.trim('/').replace('/', '\\')

    private fun requireShare(): DiskShare =
        share ?: throw TransferException(UploadErrorKind.CONNECTION_FAILED, "not connected")

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: TransferException) {
        throw e
    } catch (e: Exception) {
        throw translate(e)
    }

    private fun translate(e: Throwable): TransferException {
        val root = generateSequence(e) { it.cause }.firstOrNull { it is SMBApiException || it is IOException } ?: e
        return when (root) {
            is TransferException -> root
            is SMBApiException -> when (root.status) {
                NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_ACCOUNT_DISABLED,
                NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED ->
                    TransferException(UploadErrorKind.AUTH_FAILED, null, e)
                NtStatus.STATUS_ACCESS_DENIED -> TransferException(UploadErrorKind.PERMISSION_DENIED, null, e)
                NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_BAD_NETWORK_PATH,
                NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                NtStatus.STATUS_OBJECT_NAME_INVALID, NtStatus.STATUS_NAME_TOO_LONG ->
                    TransferException(UploadErrorKind.DESTINATION_INVALID, root.status.name, e)
                NtStatus.STATUS_OBJECT_NAME_COLLISION -> TransferException(UploadErrorKind.REMOTE_EXISTS, null, e)
                NtStatus.STATUS_DISK_FULL -> TransferException(UploadErrorKind.NO_SPACE, null, e)
                else -> TransferException(UploadErrorKind.UNKNOWN, root.status.name, e)
            }
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                TransferException(UploadErrorKind.CONNECTION_FAILED, root.javaClass.simpleName, e)
            is SocketTimeoutException -> TransferException(UploadErrorKind.TIMEOUT, null, e)
            is IOException -> TransferException(UploadErrorKind.NETWORK_LOST, root.javaClass.simpleName, e)
            is SMBRuntimeException -> TransferException(UploadErrorKind.NETWORK_LOST, root.javaClass.simpleName, e)
            else -> TransferException(UploadErrorKind.UNKNOWN, root.javaClass.simpleName, e)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60L
        private const val BUFFER_SIZE = 256 * 1024
    }
}
