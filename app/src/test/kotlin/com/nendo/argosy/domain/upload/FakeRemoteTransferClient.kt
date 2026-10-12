package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.UploadErrorKind
import java.io.ByteArrayOutputStream
import java.io.InputStream

class FakeRemoteTransferClient : RemoteTransferClient {
    val files = linkedMapOf<String, ByteArray>()
    val directories = mutableSetOf<String>()
    val operations = mutableListOf<String>()
    var failUploadAfterBytes: Long? = null
    var truncateUploadsBy: Int = 0
    var failNextUploads: Int = 0
    var failureKind: UploadErrorKind = UploadErrorKind.NETWORK_LOST

    fun dir(path: String): FakeRemoteTransferClient {
        var current = path
        while (current.isNotEmpty() && current != "/") {
            directories += current
            current = RemotePath.parent(current) ?: ""
        }
        return this
    }

    override fun connect() = Unit

    override fun stat(path: String): RemoteStat? = when {
        files.containsKey(path) -> RemoteStat(false, files.getValue(path).size.toLong())
        path in directories -> RemoteStat(true, 0)
        else -> null
    }

    override fun list(directory: String): List<RemoteEntry> =
        files.keys.filter { RemotePath.parent(it) == directory }
            .map { RemoteEntry(RemotePath.name(it), it, false, files.getValue(it).size.toLong()) }

    override fun makeDirectories(path: String) {
        operations += "mkdirs $path"
        dir(path)
    }

    override fun upload(
        source: InputStream,
        sizeBytes: Long,
        destination: String,
        progress: ProgressSink,
        cancellation: CancellationCheck
    ) {
        operations += "upload $destination"
        if (failNextUploads > 0) {
            failNextUploads--
            files[destination] = ByteArray(1)
            throw TransferException(failureKind, "injected failure")
        }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4)
        var sent = 0L
        files[destination] = ByteArray(0)
        while (true) {
            if (cancellation.isCancelled()) {
                files[destination] = out.toByteArray()
                throw TransferException(UploadErrorKind.CANCELLED)
            }
            val limit = failUploadAfterBytes
            if (limit != null && sent >= limit) {
                files[destination] = out.toByteArray()
                throw TransferException(UploadErrorKind.NETWORK_LOST, "connection reset")
            }
            val read = source.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            sent += read
            progress.onBytes(sent)
        }
        val bytes = out.toByteArray()
        files[destination] = bytes.copyOf((bytes.size - truncateUploadsBy).coerceAtLeast(0))
    }

    override fun rename(from: String, to: String) {
        operations += "rename $from -> $to"
        if (files.containsKey(to)) throw TransferException(UploadErrorKind.REMOTE_EXISTS)
        files[to] = files.remove(from) ?: throw TransferException(UploadErrorKind.DESTINATION_INVALID)
    }

    override fun delete(path: String) {
        operations += "delete $path"
        files.remove(path)
    }

    override fun availableBytes(path: String): Long? = null

    override fun close() = Unit
}
