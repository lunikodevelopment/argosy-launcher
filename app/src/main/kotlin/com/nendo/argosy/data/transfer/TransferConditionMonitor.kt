package com.nendo.argosy.data.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.nendo.argosy.domain.upload.TransferConditions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live connectivity and charging state for background transfers, from the default network callback
 * and the sticky battery broadcast. Registered once for the process lifetime.
 */
@Singleton
class TransferConditionMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _conditions = MutableStateFlow(TransferConditions(connected = true, unmetered = true, charging = false))
    val conditions: StateFlow<TransferConditions> = _conditions.asStateFlow()

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        _conditions.update { it.copy(connected = false, unmetered = false) }
        connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)?.let(::applyCapabilities)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                applyCapabilities(capabilities)
            }

            override fun onLost(network: Network) {
                _conditions.update { it.copy(connected = false, unmetered = false) }
            }
        })
        val battery = context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = applyBattery(intent)
            },
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        battery?.let(::applyBattery)
    }

    private fun applyCapabilities(capabilities: NetworkCapabilities) {
        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        _conditions.update { it.copy(connected = connected, unmetered = unmetered) }
    }

    private fun applyBattery(intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        _conditions.update { it.copy(charging = plugged != 0) }
    }
}
