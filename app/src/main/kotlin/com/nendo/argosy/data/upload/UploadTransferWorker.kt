package com.nendo.argosy.data.upload

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadJob
import com.nendo.argosy.domain.model.upload.UploadSettings
import com.nendo.argosy.domain.upload.TransferHold
import com.nendo.argosy.util.Logger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Keeps the process in the foreground while upload jobs can run, through WorkManager's dataSync
 * foreground service. It returns once nothing is runnable; jobs still waiting for a network or charger
 * queue a follow-up run gated on those constraints, which WorkManager also restores after a reboot.
 */
@HiltWorker
class UploadTransferWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploadManager: UploadManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        uploadManager.start()
        val engine = uploadManager.engine
        val initial = engine.jobs.value.filter { !it.state.isTerminal }.map { it.id }.toSet()
        runCatching { setForeground(foregroundInfo(engine.jobs.value)) }
            .onFailure { Logger.warn(TAG, "foreground refused: ${it.message}") }

        combine(engine.jobs, uploadManager.hold) { jobs, hold -> jobs to hold }
            .first { (jobs, hold) ->
                runCatching { setForeground(foregroundInfo(jobs, hold)) }
                jobs.none { it.isRunnable() }
            }

        val jobs = engine.jobs.value
        if (uploadManager.preferences.value.settings.notifyOnCompletion) {
            UploadNotifications.showSummary(applicationContext, jobs.filter { it.id in initial && it.state.isTerminal })
        }
        if (jobs.any { it.state == UploadItemState.WAITING_FOR_NETWORK }) {
            schedule(applicationContext, uploadManager.preferences.value.settings, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    private fun foregroundInfo(
        jobs: List<UploadJob>,
        hold: TransferHold = uploadManager.hold.value
    ): ForegroundInfo {
        val notification = UploadNotifications.progress(applicationContext, jobs, hold)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(UploadNotifications.PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(UploadNotifications.PROGRESS_ID, notification)
        }
    }

    private fun UploadJob.isRunnable(): Boolean =
        state == UploadItemState.PENDING || state.isActive

    companion object {
        private const val TAG = "UploadTransferWorker"
        const val WORK_NAME = "rom_upload_transfer"

        fun schedule(context: Context, settings: UploadSettings, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly)
                .build()
            val request = OneTimeWorkRequestBuilder<UploadTransferWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
