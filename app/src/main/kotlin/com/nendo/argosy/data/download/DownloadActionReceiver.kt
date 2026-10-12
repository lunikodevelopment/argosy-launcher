package com.nendo.argosy.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadManager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE -> downloadManager.pauseAllDownloads()
            ACTION_RESUME -> downloadManager.resumeAllPaused()
            ACTION_CANCEL -> downloadManager.cancelAllDownloads()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.nendo.argosy.download.PAUSE"
        const val ACTION_RESUME = "com.nendo.argosy.download.RESUME"
        const val ACTION_CANCEL = "com.nendo.argosy.download.CANCEL"
    }
}
