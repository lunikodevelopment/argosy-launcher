package com.nendo.argosy.ui.screens.settings.delegates

import com.nendo.argosy.data.preferences.UploadPreferences
import com.nendo.argosy.data.preferences.UserPreferencesRepository
import com.nendo.argosy.data.upload.ConnectionTestResult
import com.nendo.argosy.data.upload.RommScanService
import com.nendo.argosy.data.upload.UploadManager
import com.nendo.argosy.data.upload.security.UploadSecretKind
import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.nendo.argosy.domain.model.upload.SftpAuthMethod
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadJob
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.model.upload.UploadSettings
import com.nendo.argosy.ui.components.TEXT_ENTRY_CANCEL_BUTTON
import com.nendo.argosy.ui.components.TEXT_ENTRY_CONFIRM_BUTTON
import com.nendo.argosy.ui.components.TextEntryRow
import com.nendo.argosy.ui.screens.settings.RomUploadHostKeyPrompt
import com.nendo.argosy.ui.screens.settings.RomUploadJobAction
import com.nendo.argosy.ui.screens.settings.RomUploadJobMenu
import com.nendo.argosy.ui.screens.settings.RomUploadJobUi
import com.nendo.argosy.ui.screens.settings.RomUploadProfileDraft
import com.nendo.argosy.ui.screens.settings.RomUploadState
import com.nendo.argosy.ui.screens.settings.RomUploadTestPhase
import com.nendo.argosy.ui.screens.settings.RomUploadTestResult
import com.nendo.argosy.ui.screens.settings.RomUploadTextEntry
import com.nendo.argosy.ui.screens.settings.RomUploadTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RomUploadSettingsDelegate @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val uploadManager: UploadManager,
    private val scanService: RommScanService
) {
    private val _state = MutableStateFlow(RomUploadState())
    val state: StateFlow<RomUploadState> = _state.asStateFlow()

    private var pendingPassword: CharArray? = null
    private var pendingPrivateKey: CharArray? = null
    private var pendingPassphrase: CharArray? = null
    private var observing = false

    suspend fun updateFromPreferences(prefs: UploadPreferences) {
        val hasScanToken = withContext(Dispatchers.IO) { scanService.hasScanToken() }
        _state.update {
            it.copy(
                settings = prefs.settings,
                profiles = prefs.profiles,
                defaultProfileId = prefs.defaultProfile?.id,
                quickFolder = prefs.quickFolder,
                hasScanToken = hasScanToken
            )
        }
    }

    fun observe(scope: CoroutineScope) {
        if (observing) return
        observing = true
        scope.launch {
            uploadManager.preferences.collect { prefs ->
                _state.update {
                    it.copy(
                        settings = prefs.settings,
                        profiles = prefs.profiles,
                        defaultProfileId = prefs.defaultProfile?.id,
                        quickFolder = prefs.quickFolder
                    )
                }
            }
        }
        scope.launch {
            combine(uploadManager.jobs, uploadManager.preferences) { jobs, prefs ->
                val names = prefs.profiles.associate { it.id to it.name.ifBlank { it.host } }
                jobs.map { it.toUi(names[it.profileId].orEmpty()) }
            }.collect { jobs ->
                _state.update { state ->
                    val menu = state.jobMenu?.takeIf { open -> jobs.any { it.id == open.jobId && it.actions.isNotEmpty() } }
                    val openCount = jobs.count { !it.state.isTerminal }
                    state.copy(
                        openJobs = openCount,
                        jobs = jobs,
                        jobMenu = menu,
                        cancelAllArmed = state.cancelAllArmed && openCount > 0
                    )
                }
            }
        }
        scope.launch {
            uploadManager.hold.collect { hold -> _state.update { it.copy(hold = hold) } }
        }
    }

    private fun UploadJob.toUi(serverName: String) = RomUploadJobUi(
        id = id,
        title = gameTitle,
        serverName = serverName,
        state = state,
        bytesDone = files.sumOf { it.bytesTransferred },
        bytesTotal = files.sumOf { it.sizeBytes },
        errorKind = error?.kind,
        finishedAtMillis = completedAtMillis
    )

    fun openJobMenu(jobId: String): Boolean {
        val job = _state.value.jobs.firstOrNull { it.id == jobId } ?: return false
        if (job.actions.isEmpty()) return false
        _state.update { it.copy(jobMenu = RomUploadJobMenu(jobId)) }
        return true
    }

    fun moveJobMenuFocus(delta: Int) {
        _state.update { state ->
            val menu = state.jobMenu ?: return@update state
            val count = state.jobs.firstOrNull { it.id == menu.jobId }?.actions?.size ?: return@update state
            state.copy(jobMenu = menu.copy(focusIndex = (menu.focusIndex + delta).mod(count)))
        }
    }

    fun closeJobMenu() {
        _state.update { it.copy(jobMenu = null) }
    }

    fun confirmJobMenu() {
        val state = _state.value
        val menu = state.jobMenu ?: return
        val action = state.jobs.firstOrNull { it.id == menu.jobId }?.actions?.getOrNull(menu.focusIndex) ?: return
        runJobAction(menu.jobId, action)
    }

    fun runJobAction(jobId: String, action: RomUploadJobAction) {
        closeJobMenu()
        val engine = uploadManager.engine
        when (action) {
            RomUploadJobAction.PAUSE -> engine.pause(jobId)
            RomUploadJobAction.RESUME -> engine.resume(jobId)
            RomUploadJobAction.RETRY -> engine.retry(jobId)
            RomUploadJobAction.CANCEL -> engine.cancel(jobId)
        }
    }

    fun pauseAllJobs() {
        disarmCancelAll()
        uploadManager.engine.pauseAll()
    }

    fun resumeAllJobs() {
        disarmCancelAll()
        uploadManager.engine.resumeAll()
    }

    fun clearFinishedJobs() {
        disarmCancelAll()
        uploadManager.engine.clearFinished()
    }

    fun cancelAllJobs(): Boolean {
        if (!_state.value.cancelAllArmed) {
            _state.update { it.copy(cancelAllArmed = true) }
            return false
        }
        _state.update { it.copy(cancelAllArmed = false) }
        uploadManager.engine.cancelAll()
        return true
    }

    fun disarmCancelAll() {
        if (_state.value.cancelAllArmed) _state.update { it.copy(cancelAllArmed = false) }
    }

    private fun editSettings(scope: CoroutineScope, transform: (UploadSettings) -> UploadSettings, persist: suspend (UploadSettings) -> Unit) {
        val next = transform(_state.value.settings)
        if (next == _state.value.settings) return
        _state.update { it.copy(settings = next) }
        scope.launch { persist(next) }
    }

    fun cycleDuplicatePolicy(scope: CoroutineScope, direction: Int) = editSettings(scope, {
        it.copy(duplicatePolicy = DuplicatePolicy.entries.cycle(it.duplicatePolicy, direction))
    }) { preferencesRepository.setUploadDuplicatePolicy(it.duplicatePolicy) }

    fun setDuplicatePolicy(scope: CoroutineScope, index: Int) = editSettings(scope, {
        it.copy(duplicatePolicy = DuplicatePolicy.entries.getOrElse(index) { DuplicatePolicy.DEFAULT })
    }) { preferencesRepository.setUploadDuplicatePolicy(it.duplicatePolicy) }

    fun adjustConcurrency(scope: CoroutineScope, delta: Int) = editSettings(scope, {
        it.copy(maxConcurrentJobs = (it.maxConcurrentJobs + delta).coerceIn(1, UploadSettings.MAX_CONCURRENCY))
    }) { preferencesRepository.setUploadMaxConcurrent(it.maxConcurrentJobs) }

    fun cycleConcurrency(scope: CoroutineScope) = editSettings(scope, {
        it.copy(maxConcurrentJobs = if (it.maxConcurrentJobs >= UploadSettings.MAX_CONCURRENCY) 1 else it.maxConcurrentJobs + 1)
    }) { preferencesRepository.setUploadMaxConcurrent(it.maxConcurrentJobs) }

    fun toggleWifiOnly(scope: CoroutineScope) = editSettings(scope, { it.copy(wifiOnly = !it.wifiOnly) }) {
        preferencesRepository.setUploadWifiOnly(it.wifiOnly)
    }

    fun toggleChargingOnly(scope: CoroutineScope) = editSettings(scope, { it.copy(chargingOnly = !it.chargingOnly) }) {
        preferencesRepository.setUploadChargingOnly(it.chargingOnly)
    }

    fun cycleBandwidth(scope: CoroutineScope, direction: Int) = editSettings(scope, {
        it.copy(bandwidthLimitKiBps = RomUploadState.BANDWIDTH_OPTIONS_KIBPS.cycle(it.bandwidthLimitKiBps, direction))
    }) { preferencesRepository.setUploadBandwidthLimitKiBps(it.bandwidthLimitKiBps) }

    fun setBandwidth(scope: CoroutineScope, index: Int) = editSettings(scope, {
        it.copy(bandwidthLimitKiBps = RomUploadState.BANDWIDTH_OPTIONS_KIBPS.getOrElse(index) { 0 })
    }) { preferencesRepository.setUploadBandwidthLimitKiBps(it.bandwidthLimitKiBps) }

    fun cycleInGameLimit(scope: CoroutineScope, direction: Int) = editSettings(scope, {
        it.copy(inGameBandwidthLimitKiBps = RomUploadState.IN_GAME_OPTIONS_KIBPS.cycle(it.inGameBandwidthLimitKiBps, direction))
    }) { preferencesRepository.setUploadInGameBandwidthLimitKiBps(it.inGameBandwidthLimitKiBps) }

    fun setInGameLimit(scope: CoroutineScope, index: Int) = editSettings(scope, {
        it.copy(inGameBandwidthLimitKiBps = RomUploadState.IN_GAME_OPTIONS_KIBPS.getOrElse(index) { 0 })
    }) { preferencesRepository.setUploadInGameBandwidthLimitKiBps(it.inGameBandwidthLimitKiBps) }

    fun toggleScanAfterUpload(scope: CoroutineScope) = editSettings(scope, { it.copy(scanAfterUpload = !it.scanAfterUpload) }) {
        preferencesRepository.setUploadScanAfterUpload(it.scanAfterUpload)
    }

    fun toggleNotifyOnCompletion(scope: CoroutineScope) = editSettings(scope, { it.copy(notifyOnCompletion = !it.notifyOnCompletion) }) {
        preferencesRepository.setUploadNotifyOnCompletion(it.notifyOnCompletion)
    }

    fun openNewProfile() {
        clearPendingSecrets()
        val profile = UploadProfile(
            id = uploadManager.newProfileId(),
            name = "",
            protocol = TransferProtocol.SFTP,
            host = "",
            port = TransferProtocol.SFTP.defaultPort,
            username = "",
            libraryRoot = ""
        )
        _state.update {
            it.copy(
                draft = RomUploadProfileDraft(
                    profile = profile,
                    isNew = true,
                    isDefault = it.profiles.isEmpty(),
                    passwordStored = false,
                    privateKeyStored = false,
                    passphraseStored = false,
                    trustedHostKey = null
                ),
                test = RomUploadTestResult()
            )
        }
    }

    fun openProfile(scope: CoroutineScope, profileId: String) {
        clearPendingSecrets()
        val profile = _state.value.profiles.firstOrNull { it.id == profileId } ?: return
        _state.update {
            it.copy(
                draft = RomUploadProfileDraft(
                    profile = profile,
                    isNew = false,
                    isDefault = it.defaultProfileId == profileId,
                    passwordStored = false,
                    privateKeyStored = false,
                    passphraseStored = false,
                    trustedHostKey = null
                ),
                test = RomUploadTestResult()
            )
        }
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                StoredSecrets(
                    password = uploadManager.hasSecret(profileId, UploadSecretKind.PASSWORD),
                    privateKey = uploadManager.hasSecret(profileId, UploadSecretKind.PRIVATE_KEY),
                    passphrase = uploadManager.hasSecret(profileId, UploadSecretKind.KEY_PASSPHRASE),
                    hostKey = trustedKeyFor(profile)
                )
            }
            _state.update { state ->
                val draft = state.draft?.takeIf { it.profile.id == profileId } ?: return@update state
                state.copy(
                    draft = draft.copy(
                        passwordStored = draft.passwordStored || stored.password,
                        privateKeyStored = draft.privateKeyStored || stored.privateKey,
                        passphraseStored = draft.passphraseStored || stored.passphrase,
                        trustedHostKey = draft.trustedHostKey ?: stored.hostKey
                    )
                )
            }
        }
    }

    private data class StoredSecrets(
        val password: Boolean,
        val privateKey: Boolean,
        val passphrase: Boolean,
        val hostKey: HostKeyFingerprint?
    )

    fun leaveDraft(scope: CoroutineScope) {
        val draft = _state.value.draft
        if (draft != null && draft.dirty && draft.profile.isComplete) saveDraft(scope)
        closeDraft()
    }

    fun closeDraft() {
        clearPendingSecrets()
        _state.update {
            it.copy(
                draft = null,
                textEntry = null,
                hostKeyPrompt = null,
                deleteConfirmFocus = null,
                test = RomUploadTestResult(),
                keyFileBrowserOpen = false,
                keyImportFailed = false
            )
        }
    }

    private fun editDraft(transform: (UploadProfile) -> UploadProfile) {
        _state.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(draft = draft.copy(profile = transform(draft.profile), dirty = true), test = RomUploadTestResult())
        }
    }

    fun cycleProtocol(direction: Int) = editDraft { profile ->
        val next = TransferProtocol.entries.cycle(profile.protocol, direction)
        val port = if (profile.port == profile.protocol.defaultPort) next.defaultPort else profile.port
        profile.copy(protocol = next, port = port)
    }

    fun cycleSftpAuth(direction: Int) = editDraft { it.copy(sftpAuthMethod = SftpAuthMethod.entries.cycle(it.sftpAuthMethod, direction)) }

    fun cyclePlatformTemplate(direction: Int) = editDraft {
        it.copy(platformTemplate = RomUploadState.PLATFORM_TEMPLATES.cycle(it.platformTemplate, direction))
    }

    fun toggleSmbEncryption() = editDraft { it.copy(smbRequireEncryption = !it.smbRequireEncryption) }

    fun toggleCreatePlatformFolders() = editDraft { it.copy(allowCreatePlatformFolders = !it.allowCreatePlatformFolders) }

    fun toggleDefaultProfile() {
        _state.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(draft = draft.copy(isDefault = !draft.isDefault, dirty = true))
        }
    }

    fun openTextEntry(field: RomUploadTextField) {
        val profile = _state.value.draft?.profile
        val text = when (field) {
            RomUploadTextField.NAME -> profile?.name
            RomUploadTextField.HOST -> profile?.host
            RomUploadTextField.PORT -> profile?.port?.toString()
            RomUploadTextField.USERNAME -> profile?.username
            RomUploadTextField.SMB_SHARE -> profile?.smbShare
            RomUploadTextField.SMB_DOMAIN -> profile?.smbDomain
            RomUploadTextField.LIBRARY_ROOT -> profile?.libraryRoot
            RomUploadTextField.PASSWORD, RomUploadTextField.KEY_PASSPHRASE, RomUploadTextField.SCAN_TOKEN -> null
        }.orEmpty()
        _state.update { it.copy(textEntry = RomUploadTextEntry(field, text)) }
    }

    fun setTextEntryText(text: String) {
        _state.update { state -> state.copy(textEntry = state.textEntry?.copy(text = text)) }
    }

    fun moveTextEntryRow(row: TextEntryRow) {
        _state.update { state ->
            val entry = state.textEntry ?: return@update state
            state.copy(textEntry = entry.copy(focus = entry.focus.copy(row = row)))
        }
    }

    fun moveTextEntryButton(delta: Int) {
        _state.update { state ->
            val entry = state.textEntry ?: return@update state
            if (entry.focus.row != TextEntryRow.BUTTONS) return@update state
            val index = (entry.focus.buttonIndex + delta).coerceIn(TEXT_ENTRY_CANCEL_BUTTON, TEXT_ENTRY_CONFIRM_BUTTON)
            state.copy(textEntry = entry.copy(focus = entry.focus.copy(buttonIndex = index)))
        }
    }

    fun confirmTextEntryFocus(scope: CoroutineScope) {
        val entry = _state.value.textEntry ?: return
        when {
            entry.focus.row == TextEntryRow.FIELD -> moveTextEntryRow(TextEntryRow.BUTTONS)
            entry.focus.buttonIndex == TEXT_ENTRY_CANCEL_BUTTON -> closeTextEntry()
            else -> submitTextEntry(scope)
        }
    }

    fun closeTextEntry() {
        _state.update { it.copy(textEntry = null) }
    }

    fun canSubmit(entry: RomUploadTextEntry): Boolean = when (entry.field) {
        RomUploadTextField.PORT -> entry.text.trim().toIntOrNull()?.let { it in 1..MAX_PORT } == true
        RomUploadTextField.SMB_DOMAIN, RomUploadTextField.LIBRARY_ROOT -> true
        else -> entry.text.isNotBlank()
    }

    fun submitTextEntry(scope: CoroutineScope) {
        val entry = _state.value.textEntry ?: return
        if (!canSubmit(entry)) return
        val value = entry.text.trim()
        when (entry.field) {
            RomUploadTextField.NAME -> editDraft { it.copy(name = value) }
            RomUploadTextField.HOST -> editDraft { it.copy(host = value) }
            RomUploadTextField.PORT -> editDraft { it.copy(port = value.toInt()) }
            RomUploadTextField.USERNAME -> editDraft { it.copy(username = value) }
            RomUploadTextField.SMB_SHARE -> editDraft { it.copy(smbShare = value.trim('/', '\\')) }
            RomUploadTextField.SMB_DOMAIN -> editDraft { it.copy(smbDomain = value) }
            RomUploadTextField.LIBRARY_ROOT -> editDraft { it.copy(libraryRoot = value) }
            RomUploadTextField.PASSWORD -> {
                pendingPassword?.fill(' ')
                pendingPassword = entry.text.toCharArray()
                markDraft { it.copy(passwordStored = true) }
            }
            RomUploadTextField.KEY_PASSPHRASE -> {
                pendingPassphrase?.fill(' ')
                pendingPassphrase = entry.text.toCharArray()
                markDraft { it.copy(passphraseStored = true) }
            }
            RomUploadTextField.SCAN_TOKEN -> {
                val token = value.toCharArray()
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        scanService.setScanToken(token)
                        scanService.hasScanToken()
                    }
                    token.fill(' ')
                    _state.update { it.copy(hasScanToken = saved) }
                }
            }
        }
        closeTextEntry()
    }

    fun clearScanToken(scope: CoroutineScope) {
        scope.launch {
            withContext(Dispatchers.IO) { scanService.setScanToken(null) }
            _state.update { it.copy(hasScanToken = false) }
        }
    }

    private fun markDraft(transform: (RomUploadProfileDraft) -> RomUploadProfileDraft) {
        _state.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(draft = transform(draft).copy(dirty = true), test = RomUploadTestResult())
        }
    }

    fun openKeyFileBrowser() {
        _state.update { it.copy(keyFileBrowserOpen = true, keyImportFailed = false) }
    }

    fun openQuickFolderBrowser() {
        _state.update { it.copy(quickFolderBrowserOpen = true) }
    }

    fun closeQuickFolderBrowser() {
        _state.update { it.copy(quickFolderBrowserOpen = false) }
    }

    fun setQuickFolder(scope: CoroutineScope, path: String) {
        closeQuickFolderBrowser()
        scope.launch { preferencesRepository.setUploadQuickFolder(path) }
    }

    fun closeKeyFileBrowser() {
        _state.update { it.copy(keyFileBrowserOpen = false) }
    }

    fun importPrivateKey(scope: CoroutineScope, path: String) {
        closeKeyFileBrowser()
        scope.launch {
            val key = uploadManager.readPrivateKeyFile(path)
            if (key == null) {
                _state.update { it.copy(keyImportFailed = true) }
                return@launch
            }
            pendingPrivateKey?.fill(' ')
            pendingPrivateKey = key
            markDraft { it.copy(privateKeyStored = true) }
        }
    }

    fun saveDraft(scope: CoroutineScope, then: (() -> Unit)? = null) {
        val draft = _state.value.draft ?: return
        if (!draft.profile.isComplete) return
        val profile = draft.profile.copy(name = draft.profile.name.ifBlank { draft.profile.host })
        val password = pendingPassword
        val key = pendingPrivateKey
        val passphrase = pendingPassphrase
        pendingPassword = null
        pendingPrivateKey = null
        pendingPassphrase = null
        scope.launch {
            try {
                uploadManager.saveProfile(profile, password, key, passphrase)
            } finally {
                password?.fill(' ')
                key?.fill(' ')
                passphrase?.fill(' ')
            }
            if (draft.isDefault) preferencesRepository.setUploadDefaultProfile(profile.id)
            _state.update { state ->
                val current = state.draft
                if (current == null || current.profile.id != profile.id) {
                    state
                } else {
                    state.copy(draft = current.copy(profile = profile, isNew = false, dirty = false))
                }
            }
            then?.invoke()
        }
    }

    fun testConnection(scope: CoroutineScope) {
        val draft = _state.value.draft ?: return
        if (!draft.profile.isComplete) return
        _state.update { it.copy(test = RomUploadTestResult(RomUploadTestPhase.RUNNING)) }
        saveDraft(scope) {
            scope.launch { runTest(draft.profile.id) }
        }
    }

    private suspend fun runTest(profileId: String) {
        val profile = uploadManager.preferences.value.profiles.firstOrNull { it.id == profileId }
            ?: _state.value.draft?.profile?.takeIf { it.id == profileId }
            ?: return
        val result = when (val outcome = uploadManager.testConnection(profile)) {
            is ConnectionTestResult.Success ->
                RomUploadTestResult(RomUploadTestPhase.SUCCESS, outcome.platformFolders, outcome.freeBytes)
            is ConnectionTestResult.HostKeyUntrusted -> {
                _state.update { it.copy(hostKeyPrompt = RomUploadHostKeyPrompt(outcome.presented, outcome.expected)) }
                RomUploadTestResult()
            }
            is ConnectionTestResult.Failure -> RomUploadTestResult(RomUploadTestPhase.FAILED, errorKind = outcome.error.kind)
        }
        _state.update { it.copy(test = result) }
    }

    fun moveHostKeyFocus(delta: Int) {
        _state.update { state ->
            val prompt = state.hostKeyPrompt ?: return@update state
            state.copy(hostKeyPrompt = prompt.copy(focusIndex = (prompt.focusIndex + delta).coerceIn(0, 1)))
        }
    }

    fun dismissHostKeyPrompt() {
        _state.update { it.copy(hostKeyPrompt = null) }
    }

    fun confirmHostKeyFocus(scope: CoroutineScope) {
        val prompt = _state.value.hostKeyPrompt ?: return
        if (prompt.focusIndex == 0) dismissHostKeyPrompt() else trustPresentedKey(scope)
    }

    fun trustPresentedKey(scope: CoroutineScope) {
        val prompt = _state.value.hostKeyPrompt ?: return
        val profile = _state.value.draft?.profile ?: return
        _state.update { it.copy(hostKeyPrompt = null, test = RomUploadTestResult(RomUploadTestPhase.RUNNING)) }
        scope.launch {
            withContext(Dispatchers.IO) { uploadManager.trustHostKey(profile, prompt.presented) }
            _state.update { state -> state.copy(draft = state.draft?.copy(trustedHostKey = prompt.presented)) }
            runTest(profile.id)
        }
    }

    fun forgetHostKey(scope: CoroutineScope) {
        val profile = _state.value.draft?.profile ?: return
        scope.launch {
            withContext(Dispatchers.IO) { uploadManager.hostKeys.forget(profile.host.trim(), profile.port) }
            _state.update { state -> state.copy(draft = state.draft?.copy(trustedHostKey = null)) }
        }
    }

    fun requestDelete() {
        if (_state.value.draft?.isNew != false) return
        _state.update { it.copy(deleteConfirmFocus = 0) }
    }

    fun moveDeleteFocus(delta: Int) {
        _state.update { state ->
            val focus = state.deleteConfirmFocus ?: return@update state
            state.copy(deleteConfirmFocus = (focus + delta).coerceIn(0, 1))
        }
    }

    fun dismissDelete() {
        _state.update { it.copy(deleteConfirmFocus = null) }
    }

    fun confirmDeleteFocus(scope: CoroutineScope, onDeleted: () -> Unit) {
        if (_state.value.deleteConfirmFocus == 0) dismissDelete() else deleteDraftProfile(scope, onDeleted)
    }

    fun deleteDraftProfile(scope: CoroutineScope, onDeleted: () -> Unit) {
        val profileId = _state.value.draft?.profile?.id ?: return
        _state.update { it.copy(deleteConfirmFocus = null) }
        scope.launch {
            uploadManager.deleteProfile(profileId)
            closeDraft()
            onDeleted()
        }
    }

    private fun trustedKeyFor(profile: UploadProfile) =
        if (profile.protocol == TransferProtocol.SFTP) uploadManager.hostKeys.trusted(profile.host.trim(), profile.port) else null

    private fun clearPendingSecrets() {
        pendingPassword?.fill(' ')
        pendingPrivateKey?.fill(' ')
        pendingPassphrase?.fill(' ')
        pendingPassword = null
        pendingPrivateKey = null
        pendingPassphrase = null
    }

    private fun <T> List<T>.cycle(current: T, direction: Int): T {
        val index = indexOf(current).coerceAtLeast(0)
        return this[(index + direction).mod(size)]
    }

    private companion object {
        const val MAX_PORT = 65535
    }
}
