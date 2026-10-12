package com.nendo.argosy.ui.screens.settings

import androidx.lifecycle.viewModelScope
import com.nendo.argosy.core.input.SoundType
import com.nendo.argosy.ui.input.InputResult
import com.nendo.argosy.ui.screens.settings.sections.RomUploadItem
import com.nendo.argosy.ui.screens.settings.sections.RomUploadProfileItem
import com.nendo.argosy.ui.screens.settings.sections.RomUploadQueueItem
import com.nendo.argosy.ui.screens.settings.sections.romUploadProfileItemAtFocusIndex
import com.nendo.argosy.ui.screens.settings.sections.romUploadQueueItemAtFocusIndex
import com.nendo.argosy.ui.screens.settings.sections.romUploadsItemAtFocusIndex

internal fun routeRomUploadsConfirm(vm: SettingsViewModel, state: SettingsUiState): InputResult {
    val item = romUploadsItemAtFocusIndex(state.focusedIndex, state.romUpload) ?: return InputResult.HANDLED
    return routeRomUploadsActivate(vm, item)
}

internal fun routeRomUploadsActivate(vm: SettingsViewModel, item: RomUploadItem): InputResult {
    val delegate = vm.romUploadDelegate
    val scope = vm.viewModelScope
    return when (item) {
        is RomUploadItem.Header -> InputResult.HANDLED
        RomUploadItem.Queue -> {
            vm.navigateToSection(SettingsSection.ROM_UPLOAD_QUEUE)
            InputResult.HANDLED
        }
        is RomUploadItem.Profile -> {
            delegate.openProfile(scope, item.profileId)
            vm.navigateToSection(SettingsSection.ROM_UPLOAD_PROFILE)
            InputResult.HANDLED
        }
        RomUploadItem.AddProfile -> {
            delegate.openNewProfile()
            vm.navigateToSection(SettingsSection.ROM_UPLOAD_PROFILE)
            InputResult.HANDLED
        }
        RomUploadItem.DuplicatePolicy, RomUploadItem.Concurrency,
        RomUploadItem.Bandwidth, RomUploadItem.InGameLimit -> {
            routeRomUploadsCycle(vm, item, 1)
            InputResult.handled(SoundType.TOGGLE)
        }
        RomUploadItem.WifiOnly -> toggled { delegate.toggleWifiOnly(scope) }
        RomUploadItem.ChargingOnly -> toggled { delegate.toggleChargingOnly(scope) }
        RomUploadItem.NotifyOnCompletion -> toggled { delegate.toggleNotifyOnCompletion(scope) }
        RomUploadItem.ScanAfterUpload -> toggled { delegate.toggleScanAfterUpload(scope) }
        RomUploadItem.ScanToken -> {
            delegate.openTextEntry(RomUploadTextField.SCAN_TOKEN)
            InputResult.handled(SoundType.OPEN_MODAL)
        }
        RomUploadItem.RemoveScanToken -> {
            delegate.clearScanToken(scope)
            InputResult.handled(SoundType.SELECT)
        }
    }
}

internal fun routeRomUploadsCycle(vm: SettingsViewModel, item: RomUploadItem, direction: Int): Boolean {
    val delegate = vm.romUploadDelegate
    val scope = vm.viewModelScope
    when (item) {
        RomUploadItem.DuplicatePolicy -> delegate.cycleDuplicatePolicy(scope, direction)
        RomUploadItem.Concurrency -> delegate.adjustConcurrency(scope, direction)
        RomUploadItem.Bandwidth -> delegate.cycleBandwidth(scope, direction)
        RomUploadItem.InGameLimit -> delegate.cycleInGameLimit(scope, direction)
        RomUploadItem.WifiOnly -> delegate.toggleWifiOnly(scope)
        RomUploadItem.ChargingOnly -> delegate.toggleChargingOnly(scope)
        RomUploadItem.NotifyOnCompletion -> delegate.toggleNotifyOnCompletion(scope)
        RomUploadItem.ScanAfterUpload -> delegate.toggleScanAfterUpload(scope)
        else -> return false
    }
    return true
}

internal fun routeRomUploadProfileConfirm(vm: SettingsViewModel, state: SettingsUiState): InputResult {
    val item = romUploadProfileItemAtFocusIndex(state.focusedIndex, state.romUpload) ?: return InputResult.HANDLED
    return routeRomUploadProfileActivate(vm, item)
}

internal fun routeRomUploadProfileActivate(vm: SettingsViewModel, item: RomUploadProfileItem): InputResult {
    val delegate = vm.romUploadDelegate
    val scope = vm.viewModelScope
    val draft = delegate.state.value.draft ?: return InputResult.HANDLED
    return when (item) {
        is RomUploadProfileItem.Text -> {
            delegate.openTextEntry(item.field)
            InputResult.handled(SoundType.OPEN_MODAL)
        }
        RomUploadProfileItem.Protocol, RomUploadProfileItem.AuthMethod, RomUploadProfileItem.PlatformTemplate -> {
            routeRomUploadProfileCycle(vm, item, 1)
            InputResult.handled(SoundType.TOGGLE)
        }
        RomUploadProfileItem.PrivateKey -> {
            delegate.openKeyFileBrowser()
            InputResult.handled(SoundType.OPEN_MODAL)
        }
        RomUploadProfileItem.SmbEncryption -> toggled { delegate.toggleSmbEncryption() }
        RomUploadProfileItem.CreatePlatformFolders -> toggled { delegate.toggleCreatePlatformFolders() }
        RomUploadProfileItem.DefaultProfile -> toggled { delegate.toggleDefaultProfile() }
        RomUploadProfileItem.HostKey -> {
            delegate.forgetHostKey(scope)
            InputResult.handled(SoundType.SELECT)
        }
        RomUploadProfileItem.TestConnection -> {
            if (!draft.profile.isComplete) return InputResult.handled(SoundType.BOUNDARY)
            delegate.testConnection(scope)
            InputResult.handled(SoundType.SELECT)
        }
        RomUploadProfileItem.Save -> {
            if (!draft.profile.isComplete) return InputResult.handled(SoundType.BOUNDARY)
            delegate.saveDraft(scope)
            InputResult.handled(SoundType.SELECT)
        }
        RomUploadProfileItem.Delete -> {
            delegate.requestDelete()
            InputResult.handled(SoundType.OPEN_MODAL)
        }
    }
}

internal fun routeRomUploadProfileCycle(vm: SettingsViewModel, item: RomUploadProfileItem, direction: Int): Boolean {
    val delegate = vm.romUploadDelegate
    when (item) {
        RomUploadProfileItem.Protocol -> delegate.cycleProtocol(direction)
        RomUploadProfileItem.AuthMethod -> delegate.cycleSftpAuth(direction)
        RomUploadProfileItem.PlatformTemplate -> delegate.cyclePlatformTemplate(direction)
        RomUploadProfileItem.SmbEncryption -> delegate.toggleSmbEncryption()
        RomUploadProfileItem.CreatePlatformFolders -> delegate.toggleCreatePlatformFolders()
        RomUploadProfileItem.DefaultProfile -> delegate.toggleDefaultProfile()
        else -> return false
    }
    return true
}

internal fun routeRomUploadQueueConfirm(vm: SettingsViewModel, state: SettingsUiState): InputResult {
    val item = romUploadQueueItemAtFocusIndex(state.focusedIndex, state.romUpload) ?: return InputResult.HANDLED
    return routeRomUploadQueueActivate(vm, item)
}

internal fun routeRomUploadQueueActivate(vm: SettingsViewModel, item: RomUploadQueueItem): InputResult {
    val delegate = vm.romUploadDelegate
    return when (item) {
        is RomUploadQueueItem.Header, is RomUploadQueueItem.Notice, RomUploadQueueItem.Empty -> InputResult.HANDLED
        is RomUploadQueueItem.Job -> if (delegate.openJobMenu(item.jobId)) {
            InputResult.handled(SoundType.OPEN_MODAL)
        } else {
            InputResult.handled(SoundType.BOUNDARY)
        }
        RomUploadQueueItem.PauseAll -> {
            delegate.pauseAllJobs()
            InputResult.handled(SoundType.SELECT)
        }
        RomUploadQueueItem.ResumeAll -> {
            delegate.resumeAllJobs()
            InputResult.handled(SoundType.SELECT)
        }
        RomUploadQueueItem.ClearFinished -> {
            delegate.clearFinishedJobs()
            InputResult.handled(SoundType.SELECT)
        }
    }
}

private inline fun toggled(action: () -> Unit): InputResult {
    action()
    return InputResult.handled(SoundType.TOGGLE)
}
