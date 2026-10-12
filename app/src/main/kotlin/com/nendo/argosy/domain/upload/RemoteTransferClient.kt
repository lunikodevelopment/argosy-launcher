package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.UploadError
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import java.io.InputStream

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long
)

data class RemoteStat(
    val isDirectory: Boolean,
    val size: Long
)

class TransferException(
    val error: UploadError,
    cause: Throwable? = null
) : Exception(error.detail ?: error.kind.name, cause) {
    constructor(kind: UploadErrorKind, detail: String? = null, cause: Throwable? = null) :
        this(UploadError(kind, detail), cause)
}

fun interface ProgressSink {
    fun onBytes(totalSent: Long)
}

fun interface CancellationCheck {
    fun isCancelled(): Boolean

    companion object {
        val NEVER = CancellationCheck { false }
    }
}

/**
 * One authenticated connection to a remote library root. Paths are root-relative strings that have
 * already passed [RemotePath]; implementations map them onto the wire and never accept a path
 * outside the root. Every call blocks and must run off the main thread.
 */
interface RemoteTransferClient : AutoCloseable {
    fun connect()

    fun stat(path: String): RemoteStat?

    fun list(directory: String): List<RemoteEntry>

    fun makeDirectories(path: String)

    fun upload(
        source: InputStream,
        sizeBytes: Long,
        destination: String,
        progress: ProgressSink,
        cancellation: CancellationCheck
    )

    fun rename(from: String, to: String)

    fun delete(path: String)

    fun availableBytes(path: String): Long?
}
