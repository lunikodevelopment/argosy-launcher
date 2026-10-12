package com.nendo.argosy.ui.screens.settings

import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.model.upload.UploadSettings
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

data class RomUploadState(
    val settings: UploadSettings = UploadSettings(),
    val profiles: List<UploadProfile> = emptyList(),
    val defaultProfileId: String? = null,
    val hasScanToken: Boolean = false,
    val openJobs: Int = 0,
    val draft: RomUploadProfileDraft? = null,
    val textEntry: RomUploadTextEntry? = null,
    val hostKeyPrompt: RomUploadHostKeyPrompt? = null,
    val deleteConfirmFocus: Int? = null,
    val test: RomUploadTestResult = RomUploadTestResult(),
    val keyFileBrowserOpen: Boolean = false,
    val keyImportFailed: Boolean = false
) {
    val hasModal: Boolean
        get() = textEntry != null || hostKeyPrompt != null || deleteConfirmFocus != null

    companion object {
        val BANDWIDTH_OPTIONS_KIBPS = listOf(0, 256, 512, 1024, 2048, 5120, 10240)
        val IN_GAME_OPTIONS_KIBPS = listOf(0, 256, 512, 1024, 2048)
        val PLATFORM_TEMPLATES = listOf(UploadProfile.DEFAULT_PLATFORM_TEMPLATE, "{platform}/roms")
    }
}
