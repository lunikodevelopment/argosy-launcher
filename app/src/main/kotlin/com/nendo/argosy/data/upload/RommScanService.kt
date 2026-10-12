package com.nendo.argosy.data.upload

import com.nendo.argosy.data.remote.romm.RomMApi
import com.nendo.argosy.data.remote.romm.RomMApiFactory
import com.nendo.argosy.data.remote.romm.RomMConnectionManager
import com.nendo.argosy.data.remote.romm.RomMRepository
import com.nendo.argosy.data.remote.romm.RomMScanRequest
import com.nendo.argosy.data.upload.security.UploadSecretKind
import com.nendo.argosy.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LibraryScanState {
    data object Idle : LibraryScanState
    data object Scanning : LibraryScanState
    data object Refreshing : LibraryScanState
    data class Indexed(val newRoms: Int?, val gamesAdded: Int) : LibraryScanState
    data class NotScanned(val outcome: ScanOutcome) : LibraryScanState
}

/**
 * Asks RomM to scan the platform folders an upload batch wrote to, then pulls the resulting library
 * changes into Argosy. Uses the separate scan token when one is stored, otherwise the signed-in
 * session; a server older than 5.4.0 or a token without `tasks.run` ends as [LibraryScanState.NotScanned].
 */
@Singleton
class RommScanService @Inject constructor(
    private val connectionManager: RomMConnectionManager,
    private val apiFactory: RomMApiFactory,
    private val romMRepository: RomMRepository,
    private val uploadManager: UploadManager
) {
    private val coordinator = RommScanCoordinator()
    private val _state = MutableStateFlow<LibraryScanState>(LibraryScanState.Idle)
    val state: StateFlow<LibraryScanState> = _state.asStateFlow()

    suspend fun scanAndRefresh(platformFolders: List<String>): LibraryScanState {
        if (!connectionManager.isConnected()) {
            return finish(LibraryScanState.NotScanned(ScanOutcome.Failed(null)))
        }
        if (!connectionManager.isVersionAtLeast(RommScanCoordinator.MIN_SERVER_VERSION)) {
            return finish(LibraryScanState.NotScanned(ScanOutcome.Unsupported(connectionManager.getConnectedVersion())))
        }
        val api = scanApi() ?: return finish(LibraryScanState.NotScanned(ScanOutcome.Unauthorized))
        _state.value = LibraryScanState.Scanning
        val since = Instant.now()
        val outcome = coordinator.scan(RetrofitScanEndpoint(api), platformFolders)
        if (outcome !is ScanOutcome.Completed && outcome !is ScanOutcome.AlreadyRunning) {
            Logger.info(TAG, "scan not run: ${outcome.javaClass.simpleName}")
            return finish(LibraryScanState.NotScanned(outcome))
        }
        _state.value = LibraryScanState.Refreshing
        val result = runCatching { romMRepository.syncLibraryChanges(since) }.getOrNull()
        return finish(LibraryScanState.Indexed((outcome as? ScanOutcome.Completed)?.newRoms, result?.gamesAdded ?: 0))
    }

    fun hasScanToken(): Boolean = uploadManager.secrets.has(SCAN_TOKEN_OWNER, UploadSecretKind.SCAN_TOKEN)

    fun setScanToken(token: CharArray?) {
        if (token == null || token.isEmpty()) {
            uploadManager.secrets.remove(SCAN_TOKEN_OWNER, UploadSecretKind.SCAN_TOKEN)
        } else {
            uploadManager.secrets.put(SCAN_TOKEN_OWNER, UploadSecretKind.SCAN_TOKEN, token)
        }
    }

    private fun scanApi(): RomMApi? {
        val token = uploadManager.secrets.get(SCAN_TOKEN_OWNER, UploadSecretKind.SCAN_TOKEN)
        if (token != null) return apiFactory.create(connectionManager.getBaseUrl(), String(token))
        return connectionManager.getApi()
    }

    private fun finish(state: LibraryScanState): LibraryScanState {
        _state.value = state
        return state
    }

    private class RetrofitScanEndpoint(private val api: RomMApi) : ScanEndpoint {
        override suspend fun start(platformFolders: List<String>): ScanHttpResult {
            val response = api.startScan(RomMScanRequest(RommScanCoordinator.SCAN_TYPE_QUICK, platformFolders))
            val body = response.body()
            return ScanHttpResult(response.code(), body?.taskId, body?.status, null)
        }

        override suspend fun status(taskId: String): ScanHttpResult {
            val response = api.getTask(taskId)
            val body = response.body()
            return ScanHttpResult(response.code(), body?.taskId, body?.status, body?.meta?.scanStats?.newRoms)
        }
    }

    companion object {
        private const val TAG = "RommScanService"
        private const val SCAN_TOKEN_OWNER = "romm-scan"
    }
}
