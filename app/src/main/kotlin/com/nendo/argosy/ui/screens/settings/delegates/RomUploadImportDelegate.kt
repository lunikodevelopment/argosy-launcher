package com.nendo.argosy.ui.screens.settings.delegates

import com.nendo.argosy.core.input.SoundType
import com.nendo.argosy.data.repository.GameRepository
import com.nendo.argosy.data.upload.LocalGameRef
import com.nendo.argosy.data.upload.UploadManager
import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.PlannedGame
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.upload.UploadPlan
import com.nendo.argosy.ui.screens.settings.RomUploadImportKind
import com.nendo.argosy.ui.screens.settings.RomUploadImportPhase
import com.nendo.argosy.ui.screens.settings.RomUploadImportRow
import com.nendo.argosy.ui.screens.settings.RomUploadImportState
import com.nendo.argosy.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "RomUploadImportDelegate"

class RomUploadImportDelegate @Inject constructor(
    private val uploadManager: UploadManager,
    private val gameRepository: GameRepository
) {
    private val _state = MutableStateFlow(RomUploadImportState())
    val state: StateFlow<RomUploadImportState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var selectedPaths: List<String> = emptyList()
    private var missingGames: List<LocalGameRef> = emptyList()
    private var plan: UploadPlan? = null
    private var planJob: Job? = null

    fun observe(scope: CoroutineScope) {
        this.scope = scope
    }

    fun openBrowser(): Boolean {
        if (uploadManager.preferences.value.profiles.isEmpty()) return false
        _state.update { it.copy(browserOpen = true) }
        return true
    }

    fun closeBrowser() {
        _state.update { it.copy(browserOpen = false) }
    }

    fun openMissing(): Boolean {
        val scope = scope ?: return false
        if (uploadManager.preferences.value.profiles.isEmpty()) return false
        scope.launch {
            val games = gameRepository.getLocalOnlyWithFiles().mapNotNull { game ->
                game.localPath?.let { LocalGameRef(it, game.platformSlug, game.title) }
            }
            missingGames = games
            startDialog(RomUploadImportKind.MISSING)
        }
        return true
    }

    fun uploadFolder(path: String): Boolean {
        if (uploadManager.preferences.value.profiles.isEmpty()) return false
        onPathsSelected(listOf(path))
        return true
    }

    fun onPathsSelected(paths: List<String>) {
        selectedPaths = paths
        startDialog(RomUploadImportKind.PICKED)
    }

    private fun startDialog(kind: RomUploadImportKind) {
        val prefs = uploadManager.preferences.value
        val defaultProfile = prefs.defaultProfile ?: return closeBrowser()
        val names = prefs.profiles.map { it.name.ifBlank { it.host } }
        _state.value = RomUploadImportState(
            kind = kind,
            visible = true,
            serverNames = names,
            serverIndex = prefs.profiles.indexOfFirst { it.id == defaultProfile.id }.coerceAtLeast(0),
            policy = prefs.settings.duplicatePolicy
        )
        replan()
    }

    fun dismiss() {
        planJob?.cancel()
        plan = null
        selectedPaths = emptyList()
        missingGames = emptyList()
        _state.value = RomUploadImportState()
    }

    fun moveFocus(delta: Int) {
        _state.update { it.copy(focusIndex = (it.focusIndex + delta).mod(RomUploadImportRow.entries.size)) }
    }

    fun cycleFocused(direction: Int): Boolean = when (_state.value.focusedRow) {
        RomUploadImportRow.SERVER -> cycleServer(direction)
        RomUploadImportRow.POLICY -> {
            cyclePolicy(direction)
            true
        }
        RomUploadImportRow.UNCERTAIN -> {
            toggleUncertain()
            true
        }
        else -> false
    }

    fun activate(row: RomUploadImportRow): SoundType {
        _state.update { it.copy(focusIndex = row.ordinal) }
        return when (row) {
            RomUploadImportRow.SERVER -> if (cycleServer(1)) SoundType.TOGGLE else SoundType.BOUNDARY
            RomUploadImportRow.POLICY -> {
                cyclePolicy(1)
                SoundType.TOGGLE
            }
            RomUploadImportRow.UNCERTAIN -> {
                toggleUncertain()
                SoundType.TOGGLE
            }
            RomUploadImportRow.UPLOAD -> requestUpload()
            RomUploadImportRow.CANCEL -> {
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

    fun confirmOverwrite() = enqueue(overwriteConfirmed = true)

    private fun cycleServer(direction: Int): Boolean {
        val count = _state.value.serverNames.size
        if (count < 2) return false
        _state.update { it.copy(serverIndex = (it.serverIndex + direction).mod(count)) }
        replan()
        return true
    }

    private fun cyclePolicy(direction: Int) {
        val policies = DuplicatePolicy.entries
        _state.update { it.copy(policy = policies[(policies.indexOf(it.policy) + direction).mod(policies.size)]) }
    }

    private fun toggleUncertain() {
        _state.update { it.copy(includeUncertain = !it.includeUncertain) }
    }

    private fun selectedProfile(): UploadProfile? =
        uploadManager.preferences.value.profiles.getOrNull(_state.value.serverIndex)

    private fun replan() {
        val scope = scope ?: return
        val profile = selectedProfile() ?: return
        planJob?.cancel()
        _state.update { it.copy(phase = RomUploadImportPhase.PLANNING) }
        planJob = scope.launch {
            val result = try {
                if (_state.value.kind == RomUploadImportKind.MISSING) {
                    uploadManager.planLocalGames(missingGames, profile)
                } else {
                    uploadManager.planPaths(selectedPaths, profile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            plan = result
            _state.update { state ->
                if (result == null || result.games.isEmpty()) {
                    state.copy(
                        phase = RomUploadImportPhase.EMPTY,
                        unreadable = result?.unreadable?.size ?: 0
                    )
                } else {
                    val placeable = result.games.filter { it.isPlaceable }
                    state.copy(
                        phase = RomUploadImportPhase.READY,
                        confidentGames = placeable.count { !it.platform.needsConfirmation },
                        uncertainGames = placeable.count { it.platform.needsConfirmation },
                        unplaceableGames = result.games.size - placeable.size,
                        unreadable = result.unreadable.size,
                        totalBytes = placeable.sumOf { it.totalBytes }
                    )
                }
            }
        }
    }

    private fun gamesToUpload(): List<PlannedGame> {
        val state = _state.value
        return plan?.games.orEmpty().filter {
            it.isPlaceable && (state.includeUncertain || !it.platform.needsConfirmation)
        }
    }

    private fun enqueue(overwriteConfirmed: Boolean) {
        val scope = scope ?: return
        val profile = selectedProfile() ?: return
        val policy = _state.value.policy
        val games = gamesToUpload()
        dismiss()
        scope.launch {
            try {
                uploadManager.enqueue(games, profile, policy, overwriteConfirmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.warn(TAG, "enqueue failed", e)
            }
        }
    }
}
