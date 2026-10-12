package com.nendo.argosy.ui.screens.settings.delegates

import android.content.Context
import com.nendo.argosy.data.preferences.UploadPreferencesRepository
import com.nendo.argosy.data.sftpserver.SftpServerController
import com.nendo.argosy.data.sftpserver.SftpServerService
import com.nendo.argosy.data.sftpserver.SftpServerStatus
import com.nendo.argosy.ui.screens.settings.SftpServerUiState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SftpServerSettingsDelegate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: SftpServerController,
    private val uploadPreferences: UploadPreferencesRepository
) {
    private val _state = MutableStateFlow(SftpServerUiState())
    val state: StateFlow<SftpServerUiState> = _state.asStateFlow()

    private var observing = false

    fun observe(scope: CoroutineScope) {
        if (observing) return
        observing = true
        scope.launch {
            uploadPreferences.preferences.collect { prefs ->
                _state.update { it.copy(port = prefs.sftpServerPort, writable = prefs.sftpServerWritable) }
            }
        }
        scope.launch {
            controller.status.collect { status ->
                val next = when (status) {
                    is SftpServerStatus.Running -> {
                        val password = withContext(Dispatchers.IO) { String(controller.password()) }
                        SftpServerUiState(
                            running = true,
                            address = status.addresses.firstOrNull(),
                            activePort = status.port,
                            password = password
                        )
                    }
                    is SftpServerStatus.Failed -> SftpServerUiState(failure = status.reason)
                    SftpServerStatus.Stopped -> SftpServerUiState()
                }
                _state.update { it.copy(
                    running = next.running,
                    address = next.address,
                    activePort = next.activePort,
                    password = next.password,
                    failure = next.failure
                ) }
            }
        }
    }

    fun toggleServer() {
        if (_state.value.running) SftpServerService.stop(context) else SftpServerService.start(context)
    }

    fun cyclePort(scope: CoroutineScope, direction: Int) {
        val options = SftpServerUiState.PORT_OPTIONS
        val index = options.indexOf(_state.value.port).coerceAtLeast(0)
        val next = options[(index + direction).mod(options.size)]
        _state.update { it.copy(port = next) }
        scope.launch {
            uploadPreferences.setSftpServerPort(next)
            restartIfRunning()
        }
    }

    fun toggleWritable(scope: CoroutineScope) {
        val next = !_state.value.writable
        _state.update { it.copy(writable = next) }
        scope.launch {
            uploadPreferences.setSftpServerWritable(next)
            restartIfRunning()
        }
    }

    fun regeneratePassword(scope: CoroutineScope) {
        scope.launch {
            withContext(Dispatchers.IO) { controller.regeneratePassword() }
            restartIfRunning()
        }
    }

    private fun restartIfRunning() {
        if (!_state.value.running) return
        SftpServerService.stop(context)
        SftpServerService.start(context)
    }
}
