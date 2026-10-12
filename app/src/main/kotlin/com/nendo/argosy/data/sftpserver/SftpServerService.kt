package com.nendo.argosy.data.sftpserver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nendo.argosy.MainActivity
import com.nendo.argosy.R
import com.nendo.argosy.core.service.ServiceNotificationIds
import com.nendo.argosy.core.service.startForegroundServiceSafely
import com.nendo.argosy.util.SafeCoroutineScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SftpServerService : Service() {

    @Inject
    lateinit var controller: SftpServerController

    private val serviceScope = SafeCoroutineScope(Dispatchers.IO, "SftpServerService")

    override fun onCreate() {
        super.onCreate()
        registerChannel()
        startForegroundWith(buildNotification(getString(R.string.sftp_notification_starting)))
        serviceScope.launch {
            controller.start()
            controller.status.collect { status ->
                when (status) {
                    is SftpServerStatus.Running -> notify(buildNotification(runningText(status)))
                    is SftpServerStatus.Failed -> stopSelf()
                    SftpServerStatus.Stopped -> Unit
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun runningText(status: SftpServerStatus.Running): String {
        val address = status.addresses.firstOrNull() ?: getString(R.string.sftp_notification_no_address)
        return getString(R.string.sftp_notification_running, address, status.port)
    }

    private fun registerChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sftp_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWith(notification: android.app.Notification) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: android.app.Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_helm)
        .setContentTitle(getString(R.string.sftp_notification_title))
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(contentIntent())
        .addAction(0, getString(R.string.sftp_action_stop), stopIntent())
        .build()

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, SftpServerService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL_ID = "sftp_server_channel"
        private const val NOTIFICATION_ID = ServiceNotificationIds.SFTP_SERVER
        private const val ACTION_STOP = "com.nendo.argosy.sftpserver.STOP"

        fun start(context: Context) {
            context.startForegroundServiceSafely(Intent(context, SftpServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SftpServerService::class.java))
        }
    }
}
