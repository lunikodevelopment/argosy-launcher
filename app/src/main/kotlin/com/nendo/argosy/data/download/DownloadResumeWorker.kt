package com.nendo.argosy.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Brings the process back while ROM downloads are still queued. Constructing [DownloadManager]
 * restores the persisted queue and the service controller starts the foreground service, so a
 * download that was cut short by the system killing the app continues once a network is available.
 */
@HiltWorker
class DownloadResumeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloadManager: DownloadManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        withTimeoutOrNull(RESTORE_WAIT_MS) {
            downloadManager.state.first { it.activeDownloads.isNotEmpty() || it.queue.isNotEmpty() }
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "rom_download_resume"
        private const val RESTORE_WAIT_MS = 20_000L
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DownloadResumeWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
