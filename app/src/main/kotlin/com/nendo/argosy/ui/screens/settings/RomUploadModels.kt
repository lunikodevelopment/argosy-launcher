package com.nendo.argosy.ui.screens.settings

import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.model.upload.UploadSettings
import com.nendo.argosy.domain.upload.TransferHold
import com.nendo.argosy.ui.components.TextEntryFocus

enum class RomUploadTextField(val isSecret: Boolean = false) {
    NAME,
    HOST,
    PORT,
    USERNAME,
    PASSWORD(isSecret = true),
    KEY_PASSPHRASE(isSecret = true),
    SMB_SHARE,
    SMB_DOMAIN,
    LIBRARY_ROOT,
    SCAN_TOKEN(isSecret = true)
}

data class RomUploadTextEntry(
    val field: RomUploadTextField,
    val text: String,
    val focus: TextEntryFocus = TextEntryFocus()
)

data class RomUploadHostKeyPrompt(
    val presented: HostKeyFingerprint,
    val expected: HostKeyFingerprint?,
    val focusIndex: Int = 0
) {
    val isChanged: Boolean get() = expected != null
}

enum class RomUploadTestPhase { IDLE, RUNNING, SUCCESS, FAILED }

data class RomUploadTestResult(
    val phase: RomUploadTestPhase = RomUploadTestPhase.IDLE,
    val platformFolders: Int = 0,
    val freeBytes: Long? = null,
    val errorKind: UploadErrorKind? = null
)

data class RomUploadProfileDraft(
    val profile: UploadProfile,
    val isNew: Boolean,
    val isDefault: Boolean,
    val passwordStored: Boolean,
    val privateKeyStored: Boolean,
    val passphraseStored: Boolean,
    val trustedHostKey: HostKeyFingerprint?,
    val dirty: Boolean = false
)

enum class RomUploadJobAction { PAUSE, RESUME, RETRY, CANCEL }

data class RomUploadJobUi(
    val id: String,
    val title: String,
    val serverName: String,
    val state: UploadItemState,
    val bytesDone: Long,
    val bytesTotal: Long,
    val errorKind: UploadErrorKind?,
    val finishedAtMillis: Long?
) {
    val progress: Float?
        get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null

    val actions: List<RomUploadJobAction>
        get() = when {
            state == UploadItemState.PAUSED -> listOf(RomUploadJobAction.RESUME, RomUploadJobAction.CANCEL)
            state == UploadItemState.FAILED || state == UploadItemState.CANCELLED -> listOf(RomUploadJobAction.RETRY)
            state.isTerminal -> emptyList()
            else -> listOf(RomUploadJobAction.PAUSE, RomUploadJobAction.CANCEL)
        }
}

data class RomUploadJobMenu(val jobId: String, val focusIndex: Int = 0)

data class RomUploadState(
    val settings: UploadSettings = UploadSettings(),
    val profiles: List<UploadProfile> = emptyList(),
    val defaultProfileId: String? = null,
    val hasScanToken: Boolean = false,
    val openJobs: Int = 0,
    val jobs: List<RomUploadJobUi> = emptyList(),
    val hold: TransferHold = TransferHold.NONE,
    val jobMenu: RomUploadJobMenu? = null,
    val draft: RomUploadProfileDraft? = null,
    val textEntry: RomUploadTextEntry? = null,
    val hostKeyPrompt: RomUploadHostKeyPrompt? = null,
    val deleteConfirmFocus: Int? = null,
    val test: RomUploadTestResult = RomUploadTestResult(),
    val keyFileBrowserOpen: Boolean = false,
    val quickFolder: String? = null,
    val quickFolderBrowserOpen: Boolean = false,
    val keyImportFailed: Boolean = false,
    val cancelAllArmed: Boolean = false
) {
    val hasModal: Boolean
        get() = textEntry != null || hostKeyPrompt != null || deleteConfirmFocus != null || jobMenu != null

    val openJobList: List<RomUploadJobUi>
        get() = jobs.filter { !it.state.isTerminal }

    val finishedJobList: List<RomUploadJobUi>
        get() = jobs.filter { it.state.isTerminal }.sortedByDescending { it.finishedAtMillis ?: 0L }

    val hasPausedJobs: Boolean
        get() = jobs.any { it.state == UploadItemState.PAUSED }

    val hasRunningJobs: Boolean
        get() = jobs.any { !it.state.isTerminal && it.state != UploadItemState.PAUSED }

    companion object {
        val BANDWIDTH_OPTIONS_KIBPS = listOf(0, 256, 512, 1024, 2048, 5120, 10240)
        val IN_GAME_OPTIONS_KIBPS = listOf(0, 256, 512, 1024, 2048)
        val PLATFORM_TEMPLATES = listOf(UploadProfile.DEFAULT_PLATFORM_TEMPLATE, "{platform}/roms")
    }
}
