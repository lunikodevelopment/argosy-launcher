package com.nendo.argosy.data.upload

import android.content.Context
import androidx.work.ExistingWorkPolicy
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadSettings
import com.nendo.argosy.util.SafeCoroutineScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the upload manager with the app and schedules [UploadTransferWorker] whenever queued work can
 * run or is waiting on a network or charger, so transfers continue with Argosy in the background.
 */
@Singleton
class UploadServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadManager: UploadManager
) {
    private val scope = SafeCoroutineScope(Dispatchers.Default, "UploadServiceController")
    private var scheduledWith: UploadSettings? = null

    fun start() {
        UploadNotifications.registerChannel(context)
        uploadManager.start()
        scope.launch {
            combine(uploadManager.jobs, uploadManager.preferences) { jobs, prefs ->
                val pending = jobs.any {
                    it.state == UploadItemState.PENDING || it.state.isActive || it.state == UploadItemState.WAITING_FOR_NETWORK
                }
                pending to prefs.settings
            }
                .distinctUntilChanged()
                .filter { (pending, _) -> pending }
                .collect { (_, settings) ->
                    val constraintsChanged = scheduledWith != null &&
                        (scheduledWith?.wifiOnly != settings.wifiOnly || scheduledWith?.chargingOnly != settings.chargingOnly)
                    scheduledWith = settings
                    UploadTransferWorker.schedule(
                        context,
                        settings,
                        if (constraintsChanged) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
                    )
                }
        }
    }
}
