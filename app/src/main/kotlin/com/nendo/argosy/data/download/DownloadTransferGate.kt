package com.nendo.argosy.data.download

import com.nendo.argosy.data.preferences.StoragePreferencesRepository
import com.nendo.argosy.data.transfer.TransferConditionMonitor
import com.nendo.argosy.domain.upload.BandwidthLimiter
import com.nendo.argosy.domain.upload.TransferHold
import com.nendo.argosy.util.SafeCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether ROM downloads may run right now, from connectivity and the Wi-Fi-only and charging-only
 * settings, plus the shared bandwidth limiter. [hold] is what DownloadManager reacts to: any value
 * but NONE parks active downloads in the queue, and the return to NONE resumes them.
 */
@Singleton
class DownloadTransferGate @Inject constructor(
    private val conditionMonitor: TransferConditionMonitor,
    storagePreferences: StoragePreferencesRepository
) {
    private val scope = SafeCoroutineScope(Dispatchers.Default, "DownloadTransferGate")
    private val bytesPerSecond = AtomicLong(0L)

    val limiter = BandwidthLimiter({ bytesPerSecond.get() })

    val hold: StateFlow<TransferHold> = combine(
        conditionMonitor.conditions,
        storagePreferences.preferences
    ) { conditions, prefs ->
        bytesPerSecond.set(prefs.downloadBandwidthLimitKiBps.toLong() * BYTES_PER_KIB)
        TransferHold.of(conditions, prefs.downloadWifiOnly, prefs.downloadChargingOnly)
    }.stateIn(scope, SharingStarted.Eagerly, TransferHold.NONE)

    fun start() = conditionMonitor.start()

    private companion object {
        const val BYTES_PER_KIB = 1024L
    }
}
