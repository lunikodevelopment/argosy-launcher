package com.nendo.argosy.data.download

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.nendo.argosy.MainActivity
import com.nendo.argosy.R
import com.nendo.argosy.core.service.ServiceNotificationIds
import com.nendo.argosy.core.service.startForegroundServiceSafely
import com.nendo.argosy.ui.common.toNotificationText
import dagger.hilt.android.AndroidEntryPoint
import com.nendo.argosy.util.SafeCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadForegroundService : Service() {

    override fun attachBaseContext(newBase: Context) {
        val tag = com.nendo.argosy.data.preferences.SessionStateStore(newBase).getAppLanguage()
        super.attachBaseContext(com.nendo.argosy.core.locale.LocaleHelper.wrap(newBase, tag))
    }

    @Inject
    lateinit var downloadManager: DownloadManager

    @Inject
    lateinit var steamContentManager: com.nendo.argosy.data.steam.SteamContentManager

    @Inject
    lateinit var mediaDownloadManager: MediaDownloadManager

    private val serviceScope = SafeCoroutineScope(Dispatchers.Main, "DownloadForegroundService")
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRendered: Triple<String, Int, Int>? = null

    private val batchIds = mutableSetOf<Long>()

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startForegroundWithNotification(getString(R.string.sync_download_service_preparing), 0, 0)
        observeDownloadState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        wakeLock = lock
        serviceScope.launch {
            while (true) {
                if (!lock.isHeld) lock.acquire(WAKELOCK_LEASE_MS)
                delay(WAKELOCK_RENEW_MS)
                if (lock.isHeld) lock.release()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun observeDownloadState() {
        serviceScope.launch {
            combine(
                downloadManager.state,
                steamContentManager.downloadState,
                steamContentManager.activeDownload,
                mediaDownloadManager.downloadState,
                mediaDownloadManager.activeDownload
            ) { rommState, steamState, steamDl, mediaState, mediaDl ->
                DownloadServiceSnapshot(rommState, steamState, steamDl, mediaState, mediaDl)
            }
                .collect { snapshot ->
                    val rommState = snapshot.rommState
                    val steamState = snapshot.steamState
                    val steamDl = snapshot.steamDownload
                    val rommActive = rommState.activeDownloads
                    val rommQueued = rommState.queue.filter { it.state == DownloadState.QUEUED }
                    val steamBusy = steamState !is com.nendo.argosy.data.steam.SteamDownloadState.Idle &&
                        steamState !is com.nendo.argosy.data.steam.SteamDownloadState.Completed &&
                        steamState !is com.nendo.argosy.data.steam.SteamDownloadState.Failed
                    val mediaBusy = snapshot.mediaState is MediaDownloadState.Preparing ||
                        snapshot.mediaState is MediaDownloadState.Downloading

                    if (rommActive.isEmpty() && rommQueued.isEmpty() && !steamBusy && !mediaBusy) {
                        stopSelf()
                        return@collect
                    }

                    if (mediaBusy && rommActive.isEmpty() && !steamBusy) {
                        updateMediaNotification(snapshot.mediaState, snapshot.mediaDownload)
                        return@collect
                    }

                    if (steamBusy && steamDl != null) {
                        val text = steamState.toNotificationText(this@DownloadForegroundService, steamDl.gameName)
                        if (text != null) {
                            updateNotification(text, 0, 0)
                        } else if (steamState is com.nendo.argosy.data.steam.SteamDownloadState.Downloading) {
                            val pct = (steamDl.progress * 100).toInt()
                            updateNotification(
                                getString(R.string.sync_download_service_steam_downloading, steamDl.gameName),
                                pct,
                                100
                            )
                        }
                        return@collect
                    }

                    val outstanding = rommActive + rommQueued
                    batchIds.addAll(outstanding.map { it.id })
                    val batchTotal = batchIds.size
                    val batchDone = batchTotal - outstanding.size

                    val currentDownload = rommActive.firstOrNull()
                    if (currentDownload != null) {
                        val name = when (currentDownload.state) {
                            DownloadState.EXTRACTING -> getString(
                                R.string.sync_download_service_extracting, currentDownload.displayTitle
                            )
                            DownloadState.MOVING -> getString(
                                R.string.sync_download_service_moving, currentDownload.displayTitle
                            )
                            else -> getString(
                                R.string.sync_download_service_downloading, currentDownload.displayTitle
                            )
                        }
                        val inFlight = rommActive.sumOf { it.batchFraction().toDouble() }.toFloat()
                        updateNotification(
                            withBatchPosition(name, batchDone + rommActive.size, batchTotal),
                            batchPercent(batchDone + inFlight, batchTotal),
                            100
                        )
                    } else {
                        val nextQueued = rommQueued.firstOrNull()
                        val message = nextQueued
                            ?.let { getString(R.string.sync_download_service_queued, it.displayTitle) }
                            ?: getString(R.string.sync_download_service_pending)
                        updateNotification(
                            withBatchPosition(message, batchDone + 1, batchTotal),
                            batchPercent(batchDone.toFloat(), batchTotal),
                            100
                        )
                    }
                }
        }
    }

    private fun DownloadProgress.batchFraction(): Float = when (state) {
        DownloadState.EXTRACTING -> 0f
        DownloadState.MOVING -> extractionPercent
        else -> progressPercent
    }

    private fun batchPercent(done: Float, total: Int): Int =
        if (total <= 0) 0 else ((done / total) * 100).toInt().coerceIn(0, 100)

    private fun withBatchPosition(text: String, position: Int, total: Int): String =
        if (total <= 1) {
            text
        } else {
            getString(
                R.string.sync_download_service_batch_position,
                text,
                position.coerceIn(1, total),
                total
            )
        }

    private fun updateMediaNotification(state: MediaDownloadState, progress: MediaDownloadProgress?) {
        when (state) {
            is MediaDownloadState.Preparing -> updateNotification(
                getString(
                    R.string.sync_download_service_media_preparing,
                    state.detail,
                    progress?.displayTitle ?: state.itemName
                ),
                0,
                0
            )
            is MediaDownloadState.Downloading -> {
                val title = getString(
                    R.string.sync_download_service_media_downloading,
                    progress?.displayTitle ?: state.itemName
                )
                if (progress != null && progress.totalBytes > 0 && !progress.sizeIsEstimated) {
                    updateNotification(title, (state.progress * 100).toInt(), 100)
                } else {
                    updateNotification(title, 0, 0)
                }
            }
            else -> Unit
        }
    }

    private data class DownloadServiceSnapshot(
        val rommState: DownloadQueueState,
        val steamState: com.nendo.argosy.data.steam.SteamDownloadState,
        val steamDownload: com.nendo.argosy.data.steam.SteamDownloadProgress?,
        val mediaState: MediaDownloadState,
        val mediaDownload: MediaDownloadProgress?
    )

    private fun startForegroundWithNotification(
        contentText: String,
        progress: Int,
        maxProgress: Int
    ) {
        val notification = buildNotification(contentText, progress, maxProgress)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        contentText: String,
        progress: Int,
        maxProgress: Int
    ) {
        val rendered = Triple(contentText, progress, maxProgress)
        if (rendered == lastRendered) return
        lastRendered = rendered
        val notification = buildNotification(contentText, progress, maxProgress)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        contentText: String,
        progress: Int,
        maxProgress: Int
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, DownloadNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_helm)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(maxProgress, progress, progress == 0 && maxProgress == 0)
            .setContentIntent(createContentIntent())
        builder.addAction(0, getString(R.string.upload_action_pause), actionIntent(DownloadActionReceiver.ACTION_PAUSE))
        if (downloadManager.state.value.hasPaused) {
            builder.addAction(0, getString(R.string.upload_action_resume), actionIntent(DownloadActionReceiver.ACTION_RESUME))
        }
        builder.addAction(0, getString(R.string.upload_action_cancel), actionIntent(DownloadActionReceiver.ACTION_CANCEL))
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        this,
        action.hashCode(),
        Intent(this, DownloadActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val NOTIFICATION_ID = ServiceNotificationIds.DOWNLOAD
        private const val WAKELOCK_TAG = "argosy:download_wakelock"
        private const val WAKELOCK_LEASE_MS = 10 * 60 * 1000L
        private const val WAKELOCK_RENEW_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundServiceSafely(
                Intent(context, DownloadForegroundService::class.java)
            )
        }
    }
}
