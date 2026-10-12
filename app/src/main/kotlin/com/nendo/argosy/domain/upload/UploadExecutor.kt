package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadFileTask
import com.nendo.argosy.domain.model.upload.UploadItemState
import java.io.InputStream

fun interface LocalSource {
    fun open(path: String): InputStream?
}

data class FileOutcome(
    val state: UploadItemState,
    val finalPath: String? = null,
    val error: TransferException? = null
)

data class ExecutionContext(
    val root: String,
    val protocol: TransferProtocol,
    val policy: DuplicatePolicy,
    val overwriteConfirmed: Boolean,
    val allowCreateDirectories: Boolean,
    val platformDirectory: String?,
    val tempSuffixSeed: String
)

/**
 * The only code that writes to a remote library. For each file: check the destination, apply the
 * duplicate policy, upload into a hidden temporary name beside the target, compare the remote size
 * with the local size, and only then rename into place. A failure at any step leaves the final name
 * untouched and removes the temporary file where the server still answers.
 */
class UploadExecutor(
    private val client: RemoteTransferClient,
    private val source: LocalSource,
    private val stateSink: (UploadItemState) -> Unit = {}
) {
    fun execute(
        task: UploadFileTask,
        context: ExecutionContext,
        progress: ProgressSink,
        cancellation: CancellationCheck
    ): FileOutcome {
        var temp: String? = null
        try {
            stateSink(UploadItemState.CHECKING_DESTINATION)
            val target = validatedTarget(task, context)
            checkPlatformDirectory(context)
            if (cancellation.isCancelled()) return cancelled()

            val existing = client.stat(target)
            val decision = DuplicateResolver.decide(
                policy = context.policy,
                overwriteConfirmed = context.overwriteConfirmed,
                existing = existing,
                localSize = task.sizeBytes,
                keepBothName = { firstFreeName(target) }
            )
            val finalPath = when (decision) {
                DuplicateDecision.Skip -> return FileOutcome(UploadItemState.SKIPPED, target)
                DuplicateDecision.Abort -> throw TransferException(
                    UploadErrorKind.REMOTE_EXISTS,
                    "Destination exists and policy does not allow replacing it"
                )
                is DuplicateDecision.Overwrite, DuplicateDecision.Proceed -> target
                is DuplicateDecision.RenameTo -> {
                    RemotePath.requireWithin(context.root, decision.path)
                    decision.path
                }
            }

            ensureDirectories(RemotePath.parent(finalPath), context)
            temp = tempPathFor(finalPath, context.tempSuffixSeed)
            client.stat(temp)?.let { client.delete(temp) }

            stateSink(UploadItemState.UPLOADING)
            val stream = source.open(task.localPath)
                ?: throw TransferException(UploadErrorKind.SOURCE_UNREADABLE, task.relativePath)
            stream.use { client.upload(it, task.sizeBytes, temp, progress, cancellation) }
            if (cancellation.isCancelled()) {
                cleanup(temp)
                return cancelled()
            }

            stateSink(UploadItemState.VERIFYING)
            val written = client.stat(temp)
            if (written == null || written.isDirectory || written.size != task.sizeBytes) {
                throw TransferException(
                    UploadErrorKind.SIZE_MISMATCH,
                    "expected ${task.sizeBytes} bytes, remote has ${written?.size}"
                )
            }

            stateSink(UploadItemState.FINALIZING)
            if (decision is DuplicateDecision.Overwrite) client.delete(finalPath)
            client.rename(temp, finalPath)
            temp = null
            return FileOutcome(UploadItemState.COMPLETED, finalPath)
        } catch (e: TransferException) {
            temp?.let(::cleanup)
            return if (e.error.kind == UploadErrorKind.CANCELLED) cancelled() else FileOutcome(UploadItemState.FAILED, error = e)
        } catch (e: InvalidRemotePathException) {
            temp?.let(::cleanup)
            return FileOutcome(
                UploadItemState.FAILED,
                error = TransferException(UploadErrorKind.DESTINATION_INVALID, e.reason.name, e)
            )
        }
    }

    private fun validatedTarget(task: UploadFileTask, context: ExecutionContext): String {
        RemotePath.requireWithin(context.root, task.remotePath)
        val relative = task.remotePath.removePrefix(context.root).trimStart('/')
        RemotePath.relativeSegments(relative, context.protocol)
        return task.remotePath
    }

    private fun checkPlatformDirectory(context: ExecutionContext) {
        val directory = context.platformDirectory ?: return
        RemotePath.requireWithin(context.root, directory)
        val stat = client.stat(directory)
        when {
            stat == null && !context.allowCreateDirectories ->
                throw TransferException(UploadErrorKind.PLATFORM_FOLDER_MISSING, RemotePath.name(directory))
            stat != null && !stat.isDirectory ->
                throw TransferException(UploadErrorKind.DESTINATION_INVALID, RemotePath.name(directory))
        }
    }

    private fun ensureDirectories(directory: String?, context: ExecutionContext) {
        if (directory == null || directory == context.root) return
        RemotePath.requireWithin(context.root, directory)
        val stat = client.stat(directory)
        if (stat != null) {
            if (!stat.isDirectory) throw TransferException(UploadErrorKind.DESTINATION_INVALID, RemotePath.name(directory))
            return
        }
        val platform = context.platformDirectory
        val withinPlatform = platform != null && (directory == platform || directory.startsWith("$platform/"))
        if (!withinPlatform && !context.allowCreateDirectories) {
            throw TransferException(UploadErrorKind.PLATFORM_FOLDER_MISSING, RemotePath.name(directory))
        }
        client.makeDirectories(directory)
    }

    private fun firstFreeName(target: String): String? =
        DuplicateResolver.keepBothCandidates(target).firstOrNull { client.stat(it) == null }

    private fun tempPathFor(finalPath: String, seed: String): String {
        val parent = RemotePath.parent(finalPath).orEmpty()
        val name = ".argosy-${seed.take(12)}-${RemotePath.name(finalPath).take(120)}$TEMP_EXTENSION"
        return when {
            parent.isEmpty() -> name
            parent == "/" -> "/$name"
            else -> "$parent/$name"
        }
    }

    private fun cleanup(temp: String) {
        runCatching { if (client.stat(temp) != null) client.delete(temp) }
    }

    private fun cancelled() = FileOutcome(
        UploadItemState.CANCELLED,
        error = TransferException(UploadErrorKind.CANCELLED)
    )

    companion object {
        const val TEMP_EXTENSION = ".part"
    }
}
