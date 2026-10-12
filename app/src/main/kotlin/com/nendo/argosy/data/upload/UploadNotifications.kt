package com.nendo.argosy.data.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nendo.argosy.MainActivity
import com.nendo.argosy.R
import com.nendo.argosy.core.service.ServiceNotificationIds
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadJob
import com.nendo.argosy.domain.upload.TransferHold

/**
 * Notification channel and content for ROM uploads: one ongoing progress notification with
 * Pause/Resume/Cancel actions while work is queued, and one summary when a batch ends.
 */
object UploadNotifications {
    const val CHANNEL_ID = "rom_upload_channel"
    const val PROGRESS_ID = ServiceNotificationIds.UPLOAD
    private const val SUMMARY_ID = ServiceNotificationIds.UPLOAD_SUMMARY
    private const val PERCENT = 100

    fun registerChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.upload_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.upload_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun progress(context: Context, jobs: List<UploadJob>, hold: TransferHold): Notification {
        val open = jobs.filter { !it.state.isTerminal }
        val active = open.firstOrNull { it.state.isActive }
        val allPaused = open.isNotEmpty() && open.all { it.state == UploadItemState.PAUSED }
        val waiting = open.any { it.state == UploadItemState.WAITING_FOR_NETWORK }
        val text = when {
            active != null -> context.getString(R.string.upload_notification_uploading, active.gameTitle)
            allPaused -> context.getString(R.string.upload_notification_paused)
            waiting && hold == TransferHold.METERED_NETWORK -> context.getString(R.string.upload_notification_waiting_wifi)
            waiting && hold == TransferHold.NOT_CHARGING -> context.getString(R.string.upload_notification_waiting_charger)
            waiting -> context.getString(R.string.upload_notification_waiting_network)
            open.any { it.nextAttemptAtMillis != null } -> context.getString(R.string.upload_notification_retrying)
            else -> context.getString(R.string.upload_notification_preparing)
        }
        val total = open.sumOf { it.totalBytes }
        val sent = open.sumOf { it.transferredBytes }
        val percent = if (total > 0) ((sent * PERCENT) / total).toInt().coerceIn(0, PERCENT) else 0
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_helm)
            .setContentTitle(context.getString(R.string.upload_notification_title, open.size))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(PERCENT, percent, active == null && !allPaused && !waiting)
            .setContentIntent(contentIntent(context))
        if (allPaused) {
            builder.addAction(0, context.getString(R.string.upload_action_resume), action(context, UploadActionReceiver.ACTION_RESUME))
        } else {
            builder.addAction(0, context.getString(R.string.upload_action_pause), action(context, UploadActionReceiver.ACTION_PAUSE))
        }
        builder.addAction(0, context.getString(R.string.upload_action_cancel), action(context, UploadActionReceiver.ACTION_CANCEL))
        return builder.build()
    }

    fun showSummary(context: Context, finished: List<UploadJob>) {
        if (finished.isEmpty()) return
        val uploaded = finished.count { it.state == UploadItemState.COMPLETED }
        val skipped = finished.count { it.state == UploadItemState.SKIPPED }
        val failed = finished.count { it.state == UploadItemState.FAILED }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_helm)
            .setContentTitle(context.getString(R.string.upload_summary_title))
            .setContentText(context.getString(R.string.upload_summary_counts, uploaded, skipped, failed))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(SUMMARY_ID, notification) }
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        return PendingIntent.getActivity(
            context,
            PROGRESS_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun action(context: Context, action: String): PendingIntent {
        val intent = Intent(context, UploadActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
