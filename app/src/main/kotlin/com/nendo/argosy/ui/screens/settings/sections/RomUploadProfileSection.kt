package com.nendo.argosy.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.nendo.argosy.R
import com.nendo.argosy.domain.model.upload.SftpAuthMethod
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.ui.common.labelRes
import com.nendo.argosy.ui.common.platformTemplateLabelRes
import com.nendo.argosy.ui.components.ActionPreference
import com.nendo.argosy.ui.components.CyclePreference
import com.nendo.argosy.ui.components.SwitchPreference
import com.nendo.argosy.ui.components.TextEntryModal
import com.nendo.argosy.ui.primitives.ArgosyConfirmModal
import com.nendo.argosy.ui.screens.settings.RomUploadProfileDraft
import com.nendo.argosy.ui.screens.settings.RomUploadState
import com.nendo.argosy.ui.screens.settings.RomUploadTestPhase
import com.nendo.argosy.ui.screens.settings.RomUploadTextField
import com.nendo.argosy.ui.screens.settings.SettingsUiState
import com.nendo.argosy.ui.screens.settings.SettingsViewModel
import com.nendo.argosy.ui.screens.settings.components.SectionPaneLayout
import com.nendo.argosy.ui.screens.settings.routeRomUploadProfileActivate
import com.nendo.argosy.ui.screens.settings.routeRomUploadProfileCycle
import com.nendo.argosy.ui.theme.Dimens
import com.nendo.argosy.util.formatBytes

internal sealed class RomUploadProfileItem(val key: String) {
    data class Text(val field: RomUploadTextField) : RomUploadProfileItem("text-${field.name}")
    data object Protocol : RomUploadProfileItem("protocol")
    data object AuthMethod : RomUploadProfileItem("authMethod")
    data object PrivateKey : RomUploadProfileItem("privateKey")
    data object SmbEncryption : RomUploadProfileItem("smbEncryption")
    data object PlatformTemplate : RomUploadProfileItem("platformTemplate")
    data object CreatePlatformFolders : RomUploadProfileItem("createPlatformFolders")
    data object DefaultProfile : RomUploadProfileItem("defaultProfile")
    data object HostKey : RomUploadProfileItem("hostKey")
    data object TestConnection : RomUploadProfileItem("testConnection")
    data object Save : RomUploadProfileItem("save")
    data object Delete : RomUploadProfileItem("delete")
}

internal fun buildRomUploadProfileItems(draft: RomUploadProfileDraft?): List<RomUploadProfileItem> {
    if (draft == null) return emptyList()
    val profile = draft.profile
    return buildList {
        add(RomUploadProfileItem.Text(RomUploadTextField.NAME))
        add(RomUploadProfileItem.Protocol)
        add(RomUploadProfileItem.Text(RomUploadTextField.HOST))
        add(RomUploadProfileItem.Text(RomUploadTextField.PORT))
        add(RomUploadProfileItem.Text(RomUploadTextField.USERNAME))
        when (profile.protocol) {
            TransferProtocol.SFTP -> {
                add(RomUploadProfileItem.AuthMethod)
                when (profile.sftpAuthMethod) {
                    SftpAuthMethod.PASSWORD -> add(RomUploadProfileItem.Text(RomUploadTextField.PASSWORD))
                    SftpAuthMethod.PRIVATE_KEY -> {
                        add(RomUploadProfileItem.PrivateKey)
                        add(RomUploadProfileItem.Text(RomUploadTextField.KEY_PASSPHRASE))
                    }
                }
            }
            TransferProtocol.SMB -> {
                add(RomUploadProfileItem.Text(RomUploadTextField.PASSWORD))
                add(RomUploadProfileItem.Text(RomUploadTextField.SMB_SHARE))
                add(RomUploadProfileItem.Text(RomUploadTextField.SMB_DOMAIN))
                add(RomUploadProfileItem.SmbEncryption)
            }
        }
        add(RomUploadProfileItem.Text(RomUploadTextField.LIBRARY_ROOT))
        add(RomUploadProfileItem.PlatformTemplate)
        add(RomUploadProfileItem.CreatePlatformFolders)
        add(RomUploadProfileItem.DefaultProfile)
        if (profile.protocol == TransferProtocol.SFTP && draft.trustedHostKey != null) {
            add(RomUploadProfileItem.HostKey)
        }
        add(RomUploadProfileItem.TestConnection)
        add(RomUploadProfileItem.Save)
        if (!draft.isNew) add(RomUploadProfileItem.Delete)
    }
}

internal fun romUploadProfileItemAtFocusIndex(index: Int, state: RomUploadState): RomUploadProfileItem? =
    buildRomUploadProfileItems(state.draft).getOrNull(index)

internal fun romUploadProfileMaxFocusIndex(state: RomUploadState): Int =
    (buildRomUploadProfileItems(state.draft).size - 1).coerceAtLeast(0)

private fun RomUploadTextField.titleRes(): Int = when (this) {
    RomUploadTextField.NAME -> R.string.upload_profile_name_title
    RomUploadTextField.HOST -> R.string.upload_profile_host_title
    RomUploadTextField.PORT -> R.string.upload_profile_port_title
    RomUploadTextField.USERNAME -> R.string.upload_profile_username_title
    RomUploadTextField.PASSWORD -> R.string.upload_profile_password_title
    RomUploadTextField.KEY_PASSPHRASE -> R.string.upload_profile_passphrase_title
    RomUploadTextField.SMB_SHARE -> R.string.upload_profile_share_title
    RomUploadTextField.SMB_DOMAIN -> R.string.upload_profile_domain_title
    RomUploadTextField.LIBRARY_ROOT -> R.string.upload_profile_root_title
    RomUploadTextField.SCAN_TOKEN -> R.string.upload_settings_scan_token_title
}

@Composable
private fun textFieldSubtitle(field: RomUploadTextField, draft: RomUploadProfileDraft): String {
    val profile = draft.profile
    val unset = stringResource(R.string.upload_profile_value_unset)
    val stored = stringResource(R.string.upload_profile_secret_stored)
    return when (field) {
        RomUploadTextField.NAME -> profile.name.ifBlank { stringResource(R.string.upload_profile_name_empty) }
        RomUploadTextField.HOST -> profile.host.ifBlank { unset }
        RomUploadTextField.PORT -> profile.port.toString()
        RomUploadTextField.USERNAME -> profile.username.ifBlank { unset }
        RomUploadTextField.PASSWORD -> if (draft.passwordStored) stored else unset
        RomUploadTextField.KEY_PASSPHRASE ->
            if (draft.passphraseStored) stored else stringResource(R.string.upload_profile_passphrase_empty)
        RomUploadTextField.SMB_SHARE -> profile.smbShare.ifBlank { unset }
        RomUploadTextField.SMB_DOMAIN -> profile.smbDomain.ifBlank { stringResource(R.string.upload_profile_optional) }
        RomUploadTextField.LIBRARY_ROOT -> profile.libraryRoot.ifBlank { stringResource(R.string.upload_profile_root_empty) }
        RomUploadTextField.SCAN_TOKEN -> unset
    }
}

@Composable
fun RomUploadProfileSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val state = uiState.romUpload
    val draft = state.draft ?: return
    val profile = draft.profile
    val items = remember(draft) { buildRomUploadProfileItems(draft) }

    fun isFocused(item: RomUploadProfileItem): Boolean = uiState.focusedIndex == items.indexOf(item)

    fun activate(item: RomUploadProfileItem) {
        viewModel.setFocusIndex(items.indexOf(item))
        routeRomUploadProfileActivate(viewModel, item)
    }

    fun cycle(item: RomUploadProfileItem, direction: Int) {
        viewModel.setFocusIndex(items.indexOf(item))
        routeRomUploadProfileCycle(viewModel, item, direction)
    }

    SectionPaneLayout(
        items = items,
        sections = emptyList(),
        focusedIndex = uiState.focusedIndex,
        focusToListIndex = { it },
        itemKey = { it.key },
        isNavItem = { false },
        onSectionTap = {},
        modifier = Modifier.fillMaxSize().padding(Dimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
    ) { item ->
        when (item) {
            is RomUploadProfileItem.Text -> ActionPreference(
                icon = if (item.field.isSecret) Icons.Default.Key else Icons.Default.Edit,
                title = stringResource(item.field.titleRes()),
                subtitle = textFieldSubtitle(item.field, draft),
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadProfileItem.Protocol -> CyclePreference(
                title = stringResource(R.string.upload_profile_protocol_title),
                value = stringResource(profile.protocol.labelRes),
                isFocused = isFocused(item),
                onClick = { cycle(item, 1) },
                onPrev = { cycle(item, -1) }
            )
            RomUploadProfileItem.AuthMethod -> CyclePreference(
                title = stringResource(R.string.upload_profile_auth_title),
                value = stringResource(profile.sftpAuthMethod.labelRes),
                isFocused = isFocused(item),
                onClick = { cycle(item, 1) },
                onPrev = { cycle(item, -1) }
            )
            RomUploadProfileItem.PrivateKey -> ActionPreference(
                icon = Icons.Default.Key,
                title = stringResource(R.string.upload_profile_private_key_title),
                subtitle = when {
                    state.keyImportFailed -> stringResource(R.string.upload_profile_private_key_unreadable)
                    draft.privateKeyStored -> stringResource(R.string.upload_profile_secret_stored)
                    else -> stringResource(R.string.upload_profile_private_key_empty)
                },
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadProfileItem.SmbEncryption -> SwitchPreference(
                title = stringResource(R.string.upload_profile_encryption_title),
                subtitle = stringResource(R.string.upload_profile_encryption_subtitle),
                isEnabled = profile.smbRequireEncryption,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadProfileItem.PlatformTemplate -> CyclePreference(
                title = stringResource(R.string.upload_profile_template_title),
                value = stringResource(platformTemplateLabelRes(profile.platformTemplate)),
                isFocused = isFocused(item),
                onClick = { cycle(item, 1) },
                onPrev = { cycle(item, -1) }
            )
            RomUploadProfileItem.CreatePlatformFolders -> SwitchPreference(
                title = stringResource(R.string.upload_profile_create_folders_title),
                subtitle = stringResource(R.string.upload_profile_create_folders_subtitle),
                isEnabled = profile.allowCreatePlatformFolders,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadProfileItem.DefaultProfile -> SwitchPreference(
                title = stringResource(R.string.upload_profile_default_title),
                subtitle = stringResource(R.string.upload_profile_default_subtitle),
                isEnabled = draft.isDefault,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadProfileItem.HostKey -> {
                val key = draft.trustedHostKey
                ActionPreference(
                    icon = Icons.Default.VerifiedUser,
                    title = stringResource(R.string.upload_profile_host_key_title),
                    subtitle = stringResource(
                        R.string.upload_profile_host_key_subtitle,
                        key?.keyType.orEmpty(),
                        key?.sha256.orEmpty()
                    ),
                    isFocused = isFocused(item),
                    onClick = { activate(item) }
                )
            }
            RomUploadProfileItem.TestConnection -> {
                val test = state.test
                val subtitle = when {
                    !profile.isComplete -> stringResource(R.string.upload_profile_test_incomplete)
                    test.phase == RomUploadTestPhase.RUNNING -> stringResource(R.string.upload_profile_test_running)
                    test.phase == RomUploadTestPhase.SUCCESS && test.freeBytes != null -> stringResource(
                        R.string.upload_profile_test_success_space,
                        test.platformFolders,
                        formatBytes(test.freeBytes)
                    )
                    test.phase == RomUploadTestPhase.SUCCESS ->
                        stringResource(R.string.upload_profile_test_success, test.platformFolders)
                    test.phase == RomUploadTestPhase.FAILED && test.errorKind != null ->
                        stringResource(test.errorKind.labelRes)
                    else -> stringResource(R.string.upload_profile_test_idle)
                }
                ActionPreference(
                    icon = Icons.Default.NetworkCheck,
                    title = stringResource(R.string.upload_profile_test_title),
                    subtitle = subtitle,
                    isFocused = isFocused(item),
                    isEnabled = profile.isComplete && test.phase != RomUploadTestPhase.RUNNING,
                    spinIcon = test.phase == RomUploadTestPhase.RUNNING,
                    onClick = { activate(item) }
                )
            }
            RomUploadProfileItem.Save -> ActionPreference(
                icon = Icons.Default.Save,
                title = stringResource(R.string.upload_profile_save_title),
                subtitle = when {
                    !profile.isComplete -> stringResource(R.string.upload_profile_test_incomplete)
                    draft.dirty || draft.isNew -> stringResource(R.string.upload_profile_save_dirty)
                    else -> stringResource(R.string.upload_profile_save_clean)
                },
                isFocused = isFocused(item),
                isEnabled = profile.isComplete,
                onClick = { activate(item) }
            )
            RomUploadProfileItem.Delete -> ActionPreference(
                icon = Icons.Default.Delete,
                title = stringResource(R.string.upload_profile_delete_title),
                subtitle = stringResource(R.string.upload_profile_delete_subtitle),
                isFocused = isFocused(item),
                isDangerous = true,
                onClick = { activate(item) }
            )
        }
    }

    RomUploadTextEntryModal(state, viewModel)
    RomUploadHostKeyModal(state, viewModel)
    RomUploadDeleteModal(state, viewModel)
}

@Composable
internal fun RomUploadTextEntryModal(state: RomUploadState, viewModel: SettingsViewModel) {
    val entry = state.textEntry ?: return
    val delegate = viewModel.romUploadDelegate
    TextEntryModal(
        title = stringResource(entry.field.titleRes()),
        label = stringResource(entry.field.titleRes()),
        confirmLabel = stringResource(R.string.upload_text_entry_confirm),
        cancelLabel = stringResource(R.string.upload_text_entry_cancel),
        text = entry.text,
        onTextChange = { delegate.setTextEntryText(it) },
        onDismiss = { delegate.closeTextEntry() },
        onSubmit = { viewModel.submitRomUploadTextEntry() },
        focus = entry.focus,
        canSubmit = delegate.canSubmit(entry),
        keyboardType = when (entry.field) {
            RomUploadTextField.PORT -> KeyboardType.Number
            RomUploadTextField.HOST -> KeyboardType.Uri
            else -> KeyboardType.Text
        },
        secret = entry.field.isSecret
    )
}

@Composable
private fun RomUploadHostKeyModal(state: RomUploadState, viewModel: SettingsViewModel) {
    val prompt = state.hostKeyPrompt ?: return
    val host = state.draft?.profile?.host.orEmpty()
    val presented = "${prompt.presented.keyType} ${prompt.presented.sha256}"
    val expected = prompt.expected
    ArgosyConfirmModal(
        title = if (expected != null) {
            stringResource(R.string.upload_host_key_changed_title)
        } else {
            stringResource(R.string.upload_host_key_unknown_title)
        },
        message = if (expected != null) {
            stringResource(
                R.string.upload_host_key_changed_message,
                host,
                "${expected.keyType} ${expected.sha256}",
                presented
            )
        } else {
            stringResource(
                R.string.upload_host_key_unknown_message,
                host,
                prompt.presented.keyType,
                prompt.presented.sha256
            )
        },
        confirmLabel = if (expected != null) {
            stringResource(R.string.upload_host_key_trust_new)
        } else {
            stringResource(R.string.upload_host_key_trust)
        },
        onConfirm = { viewModel.trustRomUploadHostKey() },
        onDismiss = { viewModel.romUploadDelegate.dismissHostKeyPrompt() },
        focusedIndex = prompt.focusIndex,
        destructive = expected != null
    )
}

@Composable
private fun RomUploadDeleteModal(state: RomUploadState, viewModel: SettingsViewModel) {
    val focus = state.deleteConfirmFocus ?: return
    val profile = state.draft?.profile ?: return
    ArgosyConfirmModal(
        title = stringResource(R.string.upload_profile_delete_confirm_title, profile.name.ifBlank { profile.host }),
        message = stringResource(R.string.upload_profile_delete_confirm_message),
        confirmLabel = stringResource(R.string.upload_profile_delete_confirm_action),
        onConfirm = { viewModel.deleteRomUploadProfile() },
        onDismiss = { viewModel.romUploadDelegate.dismissDelete() },
        focusedIndex = focus,
        destructive = true
    )
}
