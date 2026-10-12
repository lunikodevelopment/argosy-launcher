package com.nendo.argosy.data.upload

import kotlinx.coroutines.delay

data class ScanHttpResult(
    val code: Int,
    val taskId: String?,
    val status: String?,
    val newRoms: Int?
)

interface ScanEndpoint {
    suspend fun start(platformFolders: List<String>): ScanHttpResult

    suspend fun status(taskId: String): ScanHttpResult
}

sealed interface ScanOutcome {
    data class Completed(val newRoms: Int?) : ScanOutcome
    data object AlreadyRunning : ScanOutcome
    data object NoWorker : ScanOutcome
    data object MissingPermission : ScanOutcome
    data object Unauthorized : ScanOutcome
    data class Unsupported(val serverVersion: String?) : ScanOutcome
    data class Failed(val detail: String?) : ScanOutcome
    data object TimedOut : ScanOutcome
}

/**
 * One RomM quick scan of the platform folders a batch wrote to, through `POST /api/tasks/scan`
 * (RomM 5.4.0 and later, scope `tasks.run`), followed until the task reaches an end state.
 */
class RommScanCoordinator(
    private val pollIntervalMillis: Long = DEFAULT_POLL_MILLIS,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {
    suspend fun scan(endpoint: ScanEndpoint, platformFolders: List<String>): ScanOutcome {
        val folders = platformFolders.filter { it.isNotBlank() }.distinct()
        val started = runCatching { endpoint.start(folders) }.getOrElse { return ScanOutcome.Failed(it.javaClass.simpleName) }
        when (started.code) {
            in 200..299 -> Unit
            401 -> return ScanOutcome.Unauthorized
            403 -> return ScanOutcome.MissingPermission
            404, 405 -> return ScanOutcome.Unsupported(null)
            409 -> return ScanOutcome.AlreadyRunning
            503 -> return ScanOutcome.NoWorker
            else -> return ScanOutcome.Failed("HTTP ${started.code}")
        }
        val taskId = started.taskId ?: return ScanOutcome.Completed(null)
        var waited = 0L
        while (waited < timeoutMillis) {
            delay(pollIntervalMillis)
            waited += pollIntervalMillis
            val current = runCatching { endpoint.status(taskId) }.getOrNull() ?: continue
            if (current.code == 401) return ScanOutcome.Unauthorized
            if (current.code == 403) return ScanOutcome.MissingPermission
            when (current.status) {
                STATUS_FINISHED -> return ScanOutcome.Completed(current.newRoms)
                STATUS_FAILED, STATUS_STOPPED, STATUS_CANCELED -> return ScanOutcome.Failed(current.status)
            }
        }
        return ScanOutcome.TimedOut
    }

    companion object {
        const val SCAN_TYPE_QUICK = "quick"
        const val MIN_SERVER_VERSION = "5.4.0"
        private const val STATUS_FINISHED = "finished"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_STOPPED = "stopped"
        private const val STATUS_CANCELED = "canceled"
        private const val DEFAULT_POLL_MILLIS = 3_000L
        private const val DEFAULT_TIMEOUT_MILLIS = 30 * 60_000L
    }
}
