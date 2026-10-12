package com.nendo.argosy.data.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class UploadActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var uploadManager: UploadManager

    override fun onReceive(context: Context, intent: Intent) {
        val engine = uploadManager.engine
        when (intent.action) {
            ACTION_PAUSE -> engine.pauseAll()
            ACTION_RESUME -> engine.resumeAll()
            ACTION_CANCEL -> engine.cancelAll()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.nendo.argosy.upload.PAUSE"
        const val ACTION_RESUME = "com.nendo.argosy.upload.RESUME"
        const val ACTION_CANCEL = "com.nendo.argosy.upload.CANCEL"
    }
}
