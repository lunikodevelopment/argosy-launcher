package com.nendo.argosy.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nendo.argosy.R
import com.nendo.argosy.ui.common.labelRes
import com.nendo.argosy.ui.components.InputButton
import com.nendo.argosy.ui.components.Modal
import com.nendo.argosy.ui.primitives.ArgosyConfirmModal
import com.nendo.argosy.ui.screens.gamedetail.components.OptionItem
import com.nendo.argosy.ui.screens.settings.RomUploadImportKind
import com.nendo.argosy.ui.screens.settings.RomUploadImportPhase
import com.nendo.argosy.ui.screens.settings.RomUploadImportRow
import com.nendo.argosy.ui.screens.settings.RomUploadImportState
import com.nendo.argosy.ui.screens.settings.delegates.RomUploadImportDelegate
import com.nendo.argosy.ui.theme.Dimens
import com.nendo.argosy.util.formatBytes

@Composable
internal fun RomUploadImportModal(state: RomUploadImportState, delegate: RomUploadImportDelegate) {
    if (!state.visible) return

    Modal(
        title = stringResource(
            if (state.kind == RomUploadImportKind.MISSING) R.string.upload_import_missing_title else R.string.upload_import_title
        ),
        onDismiss = { delegate.dismiss() },
        footerHints = listOf(InputButton.DPAD_HORIZONTAL to stringResource(R.string.upload_detail_footer_change)),
        onFooterHintClick = { button ->
            if (button == InputButton.DPAD_HORIZONTAL) delegate.cycleFocused(1)
        }
    ) {
        OptionItem(
            label = stringResource(R.string.upload_detail_server),
            value = state.serverName,
            isFocused = state.focusedRow == RomUploadImportRow.SERVER,
            onClick = { delegate.activate(RomUploadImportRow.SERVER) }
        )
        OptionItem(
            label = stringResource(R.string.upload_settings_duplicates_title),
            value = stringResource(state.policy.labelRes),
            isFocused = state.focusedRow == RomUploadImportRow.POLICY,
            onClick = { delegate.activate(RomUploadImportRow.POLICY) }
        )
        OptionItem(
            label = stringResource(R.string.upload_import_include_uncertain),
            subtext = stringResource(R.string.upload_import_include_uncertain_subtitle),
            value = stringResource(if (state.includeUncertain) R.string.upload_import_yes else R.string.upload_import_no),
            isFocused = state.focusedRow == RomUploadImportRow.UNCERTAIN,
            onClick = { delegate.activate(RomUploadImportRow.UNCERTAIN) }
        )
        ImportSummary(state)
        OptionItem(
            icon = Icons.Default.CloudUpload,
            label = if (state.canUpload) {
                pluralStringResource(R.plurals.upload_import_start, state.uploadCount, state.uploadCount)
            } else {
                stringResource(R.string.upload_detail_start)
            },
            isFocused = state.focusedRow == RomUploadImportRow.UPLOAD,
            isEnabled = state.canUpload,
            onClick = { delegate.activate(RomUploadImportRow.UPLOAD) }
        )
        OptionItem(
            label = stringResource(R.string.upload_detail_cancel),
            isFocused = state.focusedRow == RomUploadImportRow.CANCEL,
            onClick = { delegate.activate(RomUploadImportRow.CANCEL) }
        )
    }

    val overwriteFocus = state.overwriteConfirmFocus
    if (overwriteFocus != null) {
        ArgosyConfirmModal(
            title = stringResource(R.string.upload_detail_overwrite_title),
            message = stringResource(R.string.upload_import_overwrite_message, state.uploadCount, state.serverName),
            confirmLabel = stringResource(R.string.upload_detail_overwrite_confirm),
            onConfirm = { delegate.confirmOverwrite() },
            onDismiss = { delegate.dismissOverwrite() },
            focusedIndex = overwriteFocus,
            destructive = true
        )
    }
}

@Composable
private fun ImportSummary(state: RomUploadImportState) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacingMd, vertical = Dimens.spacingSm)
    ) {
        when (state.phase) {
            RomUploadImportPhase.PLANNING -> Note(stringResource(R.string.upload_detail_planning))
            RomUploadImportPhase.EMPTY -> if (state.kind == RomUploadImportKind.MISSING) {
                Note(stringResource(R.string.upload_import_missing_none))
            } else {
                Note(stringResource(R.string.upload_import_nothing_found), warning = true)
            }
            RomUploadImportPhase.READY -> {
                Note(
                    pluralStringResource(
                        R.plurals.upload_import_ready,
                        state.confidentGames,
                        state.confidentGames,
                        formatBytes(state.totalBytes)
                    )
                )
                if (state.uncertainGames > 0) {
                    Note(
                        pluralStringResource(R.plurals.upload_import_uncertain, state.uncertainGames, state.uncertainGames),
                        warning = !state.includeUncertain
                    )
                }
                if (state.unplaceableGames > 0) {
                    Note(
                        pluralStringResource(R.plurals.upload_import_unplaceable, state.unplaceableGames, state.unplaceableGames),
                        warning = true
                    )
                }
                if (state.unreadable > 0) {
                    Note(
                        pluralStringResource(R.plurals.upload_import_unreadable, state.unreadable, state.unreadable),
                        warning = true
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
