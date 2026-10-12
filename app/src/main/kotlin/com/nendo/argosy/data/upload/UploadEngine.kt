package com.nendo.argosy.data.upload

import com.nendo.argosy.domain.model.upload.UploadError
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadFileTask
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadJob
import com.nendo.argosy.domain.upload.BandwidthLimiter
import com.nendo.argosy.domain.upload.CancellationCheck
import com.nendo.argosy.domain.upload.ExecutionContext
import com.nendo.argosy.domain.upload.LocalSource
import com.nendo.argosy.domain.upload.RemoteTransferClient
import com.nendo.argosy.domain.upload.RetryPolicy
import com.nendo.argosy.domain.upload.TransferException
import com.nendo.argosy.domain.upload.UploadExecutor
import com.nendo.argosy.domain.upload.UploadJobs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

fun interface TransferClientFactory {
    fun create(profileId: String): RemoteTransferClient
}

interface UploadJobStore {
    fun load(): List<UploadJob>

    fun save(jobs: List<UploadJob>)
}

data class UploadRate(
    val bytesPerSecond: Long,
    val etaSeconds: Long?
)

/**
 * Persistent upload queue runner with a concurrency cap, retry backoff and a network wait state.
 * An interrupted file restarts from zero; files already renamed into place are never sent again.
 */
class UploadEngine(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val store: UploadJobStore,
    private val clientFactory: TransferClientFactory,
    private val localSource: LocalSource,
    private val limiter: BandwidthLimiter,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val workerScope: (() -> Unit) -> Unit = { it() }
) {
    private enum class StopReason { PAUSE, CANCEL, NETWORK }

    private val lock = Any()
    private val _jobs = MutableStateFlow<List<UploadJob>>(emptyList())
    val jobs: StateFlow<List<UploadJob>> = _jobs.asStateFlow()

    private val _rates = MutableStateFlow<Map<String, UploadRate>>(emptyMap())
    val rates: StateFlow<Map<String, UploadRate>> = _rates.asStateFlow()

    private val running = ConcurrentHashMap<String, Job>()
    private val stopRequests = ConcurrentHashMap<String, StopReason>()
    @Volatile
    private var wakeup: Job? = null

    @Volatile
    var maxConcurrent: Int = 1
        set(value) {
            field = value.coerceAtLeast(1)
            dispatch()
        }

    @Volatile
    var networkAvailable: Boolean = true
        private set

    fun start() {
        val restored = runCatching { store.load() }.getOrDefault(emptyList()).map(UploadJobs::recoverAfterRestart)
        synchronized(lock) { _jobs.value = restored }
        persist()
        dispatch()
    }

    fun enqueue(newJobs: List<UploadJob>) {
        if (newJobs.isEmpty()) return
        mutate { current ->
            val ids = current.map { it.id }.toSet()
            current + newJobs.filter { it.id !in ids }
        }
        dispatch()
    }

    fun pause(jobId: String) {
        if (running.containsKey(jobId)) {
            stopRequests[jobId] = StopReason.PAUSE
        } else {
            updateJob(jobId) { if (it.state.isTerminal) it else it.copy(state = UploadItemState.PAUSED) }
        }
    }

    fun resume(jobId: String) {
        updateJob(jobId) { job ->
            if (job.state == UploadItemState.PAUSED) job.copy(state = UploadItemState.PENDING) else job
        }
        dispatch()
    }

    fun cancel(jobId: String) {
        if (running.containsKey(jobId)) {
            stopRequests[jobId] = StopReason.CANCEL
        } else {
            updateJob(jobId) { job ->
                if (job.state.isTerminal) job else job.copy(
                    state = UploadItemState.CANCELLED,
                    completedAtMillis = clock(),
                    files = job.files.map { if (it.state.isTerminal) it else it.copy(state = UploadItemState.CANCELLED) }
                )
            }
        }
    }

    fun retry(jobId: String) {
        updateJob(jobId) { job ->
            if (job.state == UploadItemState.FAILED || job.state == UploadItemState.CANCELLED) {
                UploadJobs.resetForRetry(job).copy(attempts = 0)
            } else {
                job
            }
        }
        dispatch()
    }

    fun pauseAll() = _jobs.value.filter { !it.state.isTerminal }.forEach { pause(it.id) }

    fun resumeAll() = _jobs.value.filter { it.state == UploadItemState.PAUSED }.forEach { resume(it.id) }

    fun cancelAll() = _jobs.value.filter { !it.state.isTerminal }.forEach { cancel(it.id) }

    fun clearFinished() = mutate { current -> current.filter { !it.state.isTerminal } }

    fun setNetworkAvailable(available: Boolean) {
        if (networkAvailable == available) return
        networkAvailable = available
        if (available) {
            mutate { current ->
                current.map { job ->
                    if (job.state == UploadItemState.WAITING_FOR_NETWORK) {
                        job.copy(state = UploadItemState.PENDING, nextAttemptAtMillis = null)
                    } else {
                        job
                    }
                }
            }
            dispatch()
        } else {
            running.keys.forEach { stopRequests.putIfAbsent(it, StopReason.NETWORK) }
            mutate { current ->
                current.map { job ->
                    if (job.state == UploadItemState.PENDING) job.copy(state = UploadItemState.WAITING_FOR_NETWORK) else job
                }
            }
        }
    }

    val hasUnfinishedWork: Boolean
        get() = _jobs.value.any { !it.state.isTerminal && it.state != UploadItemState.PAUSED }

    private fun dispatch() {
        val toStart = synchronized(lock) {
            if (!networkAvailable) return
            val now = clock()
            val slots = maxConcurrent - running.size
            if (slots <= 0) return
            _jobs.value
                .filter { it.state == UploadItemState.PENDING && !running.containsKey(it.id) }
                .filter { (it.nextAttemptAtMillis ?: 0L) <= now }
                .take(slots)
                .onEach { job -> running[job.id] = Job() }
        }
        toStart.forEach { job ->
            running[job.id] = scope.launch(dispatcher) {
                try {
                    workerScope { runJob(job.id) }
                } finally {
                    running.remove(job.id)
                    stopRequests.remove(job.id)
                    _rates.update { it - job.id }
                    dispatch()
                }
            }
        }
        scheduleWakeup()
    }

    private fun scheduleWakeup() {
        val next = _jobs.value
            .filter { it.state == UploadItemState.PENDING && !running.containsKey(it.id) }
            .mapNotNull { it.nextAttemptAtMillis }
            .minOrNull() ?: return
        wakeup?.cancel()
        wakeup = scope.launch(dispatcher) {
            delay((next - clock()).coerceAtLeast(0))
            dispatch()
        }
    }

    private fun runJob(jobId: String) {
        val cancellation = CancellationCheck { stopRequests.containsKey(jobId) }
        updateJob(jobId) { it.copy(state = UploadItemState.CONNECTING, error = null, attempts = it.attempts + 1) }
        val client = try {
            clientFactory.create(currentJob(jobId)?.profileId ?: return).also { it.connect() }
        } catch (e: TransferException) {
            finishWithError(jobId, e.error)
            return
        } catch (e: Exception) {
            finishWithError(jobId, UploadError(UploadErrorKind.CONNECTION_FAILED, e.javaClass.simpleName))
            return
        }
        client.use { connected ->
            val executor = UploadExecutor(connected, { path -> localSource.open(path)?.let(limiter::wrap) }) { state ->
                updateJob(jobId) { if (it.state.isTerminal) it else it.copy(state = state) }
            }
            while (true) {
                val job = currentJob(jobId) ?: return
                val task = job.files.firstOrNull { !it.state.isTerminal } ?: break
                if (cancellation.isCancelled()) break
                updateFile(jobId, task.id) { it.copy(attempts = it.attempts + 1, bytesTransferred = 0, error = null) }
                val tracker = RateTracker(clock, job.totalBytes)
                val outcome = executor.execute(
                    task = task,
                    context = contextFor(job),
                    progress = { sent ->
                        updateFile(jobId, task.id, persist = false) { it.copy(bytesTransferred = sent) }
                        currentJob(jobId)?.let { current ->
                            _rates.update { rates -> rates + (jobId to tracker.sample(current.transferredBytes)) }
                        }
                    },
                    cancellation = cancellation
                )
                updateFile(jobId, task.id) { file ->
                    file.copy(
                        state = outcome.state,
                        finalRemotePath = outcome.finalPath ?: file.finalRemotePath,
                        bytesTransferred = if (outcome.state == UploadItemState.COMPLETED) file.sizeBytes else file.bytesTransferred,
                        error = outcome.error?.error
                    )
                }
                when (outcome.state) {
                    UploadItemState.COMPLETED, UploadItemState.SKIPPED -> Unit
                    UploadItemState.CANCELLED -> break
                    else -> {
                        finishWithError(jobId, outcome.error?.error ?: UploadError(UploadErrorKind.UNKNOWN))
                        return
                    }
                }
            }
        }
        val stop = stopRequests[jobId]
        if (stop != null) {
            applyStop(jobId, stop)
            return
        }
        updateJob(jobId) { job ->
            job.copy(state = UploadJobs.finalState(job), completedAtMillis = clock(), error = null)
        }
    }

    private fun applyStop(jobId: String, reason: StopReason) {
        updateJob(jobId) { job ->
            val reset = job.files.map { file ->
                if (file.state.isTerminal && file.state != UploadItemState.CANCELLED) {
                    file
                } else {
                    file.copy(
                        state = if (reason == StopReason.CANCEL) UploadItemState.CANCELLED else UploadItemState.PENDING,
                        bytesTransferred = 0,
                        error = null
                    )
                }
            }
            when (reason) {
                StopReason.PAUSE -> job.copy(state = UploadItemState.PAUSED, files = reset)
                StopReason.NETWORK -> job.copy(state = UploadItemState.WAITING_FOR_NETWORK, files = reset)
                StopReason.CANCEL -> job.copy(state = UploadItemState.CANCELLED, files = reset, completedAtMillis = clock())
            }
        }
    }

    private fun finishWithError(jobId: String, error: UploadError) {
        stopRequests[jobId]?.let {
            applyStop(jobId, it)
            return
        }
        updateJob(jobId) { job ->
            when {
                error.kind.retryable && !networkAvailable ->
                    UploadJobs.resetForRetry(job).copy(state = UploadItemState.WAITING_FOR_NETWORK, error = error)
                retryPolicy.shouldRetry(error, job.attempts) ->
                    UploadJobs.resetForRetry(job).copy(
                        error = error,
                        nextAttemptAtMillis = clock() + retryPolicy.delayFor(job.attempts)
                    )
                else -> job.copy(state = UploadItemState.FAILED, error = error, completedAtMillis = clock())
            }
        }
    }

    private fun contextFor(job: UploadJob) = ExecutionContext(
        root = job.libraryRoot,
        protocol = job.protocol,
        policy = job.duplicatePolicy,
        overwriteConfirmed = job.overwriteConfirmed,
        allowCreateDirectories = job.allowCreatePlatformFolder,
        platformDirectory = job.platformDirectory,
        tempSuffixSeed = job.id
    )

    private fun currentJob(jobId: String): UploadJob? = _jobs.value.firstOrNull { it.id == jobId }

    private fun updateJob(jobId: String, persist: Boolean = true, transform: (UploadJob) -> UploadJob) {
        mutate(persist) { current -> current.map { if (it.id == jobId) transform(it) else it } }
    }

    private fun updateFile(
        jobId: String,
        fileId: String,
        persist: Boolean = true,
        transform: (UploadFileTask) -> UploadFileTask
    ) = updateJob(jobId, persist) { job ->
        job.copy(files = job.files.map { if (it.id == fileId) transform(it) else it })
    }

    private fun mutate(persist: Boolean = true, transform: (List<UploadJob>) -> List<UploadJob>) {
        synchronized(lock) {
            _jobs.value = transform(_jobs.value)
        }
        if (persist) persist()
    }

    private fun persist() {
        val snapshot = synchronized(lock) { _jobs.value }
        runCatching { store.save(snapshot) }
    }

    private class RateTracker(private val clock: () -> Long, private val totalBytes: Long) {
        private val startMillis = clock()
        private var startBytes = -1L

        fun sample(transferred: Long): UploadRate {
            if (startBytes < 0) startBytes = transferred
            val elapsed = clock() - startMillis
            val rate = if (elapsed > 0) (transferred - startBytes) * MILLIS_PER_SECOND / elapsed else 0
            val eta = if (rate > 0) (totalBytes - transferred).coerceAtLeast(0) / rate else null
            return UploadRate(rate, eta)
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
