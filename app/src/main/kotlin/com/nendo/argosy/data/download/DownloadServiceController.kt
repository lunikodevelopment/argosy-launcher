package com.nendo.argosy.data.download

import android.content.Context
import com.nendo.argosy.data.steam.SteamContentManager
import com.nendo.argosy.data.steam.SteamDownloadState
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nendo.argosy.util.SafeCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
    private val thermalManager: DownloadThermalManager,
    private val steamContentManager: SteamContentManager
) {
    private val scope = SafeCoroutineScope(Dispatchers.Main, "DownloadServiceController")
    private var isServiceRunning = false

    fun start() {
        thermalManager.start()
        DownloadNotificationChannel.create(context)
        observeDownloadState()
    }

    private fun observeDownloadState() {
        scope.launch {
            combine(
                downloadManager.state.map { state ->
                    state.activeDownloads.isNotEmpty() ||
                        state.queue.any { it.state == DownloadState.QUEUED }
                },
                steamContentManager.downloadState.map { state ->
                    state !is SteamDownloadState.Idle &&
                        state !is SteamDownloadState.Completed &&
                        state !is SteamDownloadState.Failed
                }
            ) { rommActive, steamActive -> rommActive || steamActive }
                .distinctUntilChanged()
                .collect { hasActiveWork ->
                    if (hasActiveWork) DownloadResumeWorker.schedule(context) else DownloadResumeWorker.cancel(context)
                    if (hasActiveWork && !isServiceRunning) {
                        isServiceRunning = true
                        DownloadForegroundService.start(context)
                    } else if (!hasActiveWork && isServiceRunning) {
                        isServiceRunning = false
                    }
                }
        }
    }
}
