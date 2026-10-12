package com.nendo.argosy.ui.screens.gamedetail.delegates

import com.nendo.argosy.R
import com.nendo.argosy.core.input.SoundType
import com.nendo.argosy.core.notification.NotificationManager
import com.nendo.argosy.core.notification.NotificationText
import com.nendo.argosy.core.notification.showError
import com.nendo.argosy.core.notification.showSuccess
import com.nendo.argosy.data.repository.GameRepository
import com.nendo.argosy.data.upload.UploadManager
import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.PlannedGame
import com.nendo.argosy.domain.model.upload.UploadProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RommUploadServer(val id: String, val name: String)

enum class RommUploadPhase { PLANNING, READY, UNAVAILABLE }

enum class RommUploadRow { SERVER, POLICY, UPLOAD, CANCEL }

data class RommUploadState(
    val hasProfiles: Boolean = false,
    val visible: Boolean = false,
    val phase: RommUploadPhase = RommUploadPhase.PLANNING,
    val gameTitle: String = "",
    val platformName: String = "",
    val servers: List<RommUploadServer> = emptyList(),
    val serverIndex: Int = 0,
    val policy: DuplicatePolicy = DuplicatePolicy.DEFAULT,
    val game: PlannedGame? = null,
    val missingFiles: List<String> = emptyList(),
    val focusIndex: Int = 0,
    val overwriteConfirmFocus: Int? = null
) {
    val server: RommUploadServer? get() = servers.getOrNull(serverIndex)
    val canUpload: Boolean get() = phase == RommUploadPhase.READY && game?.isPlaceable == true
    val focusedRow: RommUploadRow get() = RommUploadRow.entries[focusIndex]
}

class RommUploadDelegate @Inject constructor(
    private val uploadManager: UploadManager,
    private val gameRepository: GameRepository,
    private val notificationManager: NotificationManager
) {
    private val _state = MutableStateFlow(RommUploadState())
    val state: StateFlow<RommUploadState> = _state.asStateFlow()

    private data class Target(val localPath: String, val platformSlug: String, val title: String)

    private var target: Target? = null
    private var planJob: Job? = null
    private var scope: CoroutineScope? = null

    fun observe(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            uploadManager.preferences.collect { prefs ->
                _state.update { it.copy(hasProfiles = prefs.profiles.isNotEmpty()) }
            }
        }
    }

    fun open(gameId: Long, platformName: String) {
        val scope = scope ?: return
        val prefs = uploadManager.preferences.value
        val defaultProfile = prefs.defaultProfile ?: return
        val servers = prefs.profiles.map { RommUploadServer(it.id, it.name.ifBlank { it.host }) }
        target = null
        _state.update {
            RommUploadState(
                hasProfiles = true,
                visible = true,
                platformName = platformName,
                servers = servers,
                serverIndex = servers.indexOfFirst { server -> server.id == defaultProfile.id }.coerceAtLeast(0),
                policy = prefs.settings.duplicatePolicy
            )
        }
        scope.launch {
            val game = gameRepository.getById(gameId)
            val localPath = game?.localPath
            if (game == null || localPath == null) {
                _state.update { it.copy(phase = RommUploadPhase.UNAVAILABLE) }
                return@launch
            }
            target = Target(localPath, game.platformSlug, game.title)
            _state.update { it.copy(gameTitle = game.title) }
            replan()
        }
    }

    fun dismiss() {
        planJob?.cancel()
        target = null
        _state.update { RommUploadState(hasProfiles = it.hasProfiles) }
    }

    fun moveFocus(delta: Int) {
        _state.update { it.copy(focusIndex = (it.focusIndex + delta).mod(RommUploadRow.entries.size)) }
    }

    fun cycleFocused(direction: Int): Boolean = when (_state.value.focusedRow) {
        RommUploadRow.SERVER -> cycleServer(direction)
        RommUploadRow.POLICY -> {
            cyclePolicy(direction)
            true
        }
        else -> false
    }

    fun cycleServer(direction: Int): Boolean {
        val servers = _state.value.servers
        if (servers.size < 2) return false
        _state.update { it.copy(serverIndex = (it.serverIndex + direction).mod(servers.size)) }
        replan()
        return true
    }

    fun cyclePolicy(direction: Int) {
        val policies = DuplicatePolicy.entries
        _state.update { it.copy(policy = policies[(policies.indexOf(it.policy) + direction).mod(policies.size)]) }
    }

    fun activate(row: RommUploadRow): SoundType {
        _state.update { it.copy(focusIndex = row.ordinal) }
        return when (row) {
            RommUploadRow.SERVER -> if (cycleServer(1)) SoundType.TOGGLE else SoundType.BOUNDARY
            RommUploadRow.POLICY -> {
                cyclePolicy(1)
                SoundType.TOGGLE
            }
            RommUploadRow.UPLOAD -> requestUpload()
            RommUploadRow.CANCEL -> {
                dismiss()
                SoundType.CLOSE_MODAL
            }
        }
    }

    fun confirmFocused(): SoundType = activate(_state.value.focusedRow)

    fun requestUpload(): SoundType {
        val state = _state.value
        if (!state.canUpload) return SoundType.BOUNDARY
        if (state.policy == DuplicatePolicy.OVERWRITE) {
            _state.update { it.copy(overwriteConfirmFocus = 0) }
            return SoundType.OPEN_MODAL
        }
        enqueue(overwriteConfirmed = false)
        return SoundType.SELECT
    }

    fun moveOverwriteFocus(delta: Int) {
        _state.update { state ->
            val focus = state.overwriteConfirmFocus ?: return@update state
            state.copy(overwriteConfirmFocus = (focus + delta).coerceIn(0, 1))
        }
    }

    fun dismissOverwrite() {
        _state.update { it.copy(overwriteConfirmFocus = null) }
    }

    fun confirmOverwriteFocus() {
        if (_state.value.overwriteConfirmFocus == 0) dismissOverwrite() else confirmOverwrite()
    }

    fun confirmOverwrite() {
        enqueue(overwriteConfirmed = true)
    }

    private fun selectedProfile(): UploadProfile? {
        val id = _state.value.server?.id ?: return null
        return uploadManager.preferences.value.profiles.firstOrNull { it.id == id }
    }

    private fun replan() {
        val scope = scope ?: return
        val target = target ?: return
        val profile = selectedProfile() ?: return
        planJob?.cancel()
        _state.update { it.copy(phase = RommUploadPhase.PLANNING, game = null, missingFiles = emptyList()) }
        planJob = scope.launch {
            val plan = try {
                uploadManager.planLibraryGame(target.localPath, target.platformSlug, target.title, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val game = plan?.games?.singleOrNull()
            _state.update {
                if (plan == null || game == null) {
                    it.copy(phase = RommUploadPhase.UNAVAILABLE)
                } else {
                    it.copy(
                        phase = RommUploadPhase.READY,
                        game = game,
                        missingFiles = plan.missingReferences.values.flatten()
                    )
                }
            }
        }
    }

    private fun enqueue(overwriteConfirmed: Boolean) {
        val scope = scope ?: return
        val state = _state.value
        val game = state.game ?: return
        val profile = selectedProfile() ?: return
        val serverName = state.server?.name.orEmpty()
        dismiss()
        scope.launch {
            val queued = try {
                uploadManager.enqueue(listOf(game), profile, state.policy, overwriteConfirmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0
            }
            if (queued > 0) {
                notificationManager.showSuccess(
                    NotificationText.Res(R.string.upload_detail_queued, listOf(state.gameTitle, serverName))
                )
            } else {
                notificationManager.showError(
                    NotificationText.Res(R.string.upload_detail_queue_failed, listOf(state.gameTitle))
                )
            }
        }
    }
}
