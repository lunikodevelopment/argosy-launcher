package com.nendo.argosy.ui.screens.gamedetail.modals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nendo.argosy.R
import com.nendo.argosy.core.input.SoundType
import com.nendo.argosy.ui.common.labelRes
import com.nendo.argosy.ui.components.InputButton
import com.nendo.argosy.ui.components.Modal
import com.nendo.argosy.ui.input.InputHandler
import com.nendo.argosy.ui.input.InputResult
import com.nendo.argosy.ui.input.LocalInputDispatcher
import com.nendo.argosy.ui.primitives.ArgosyConfirmModal
import com.nendo.argosy.ui.screens.gamedetail.components.OptionItem
import com.nendo.argosy.ui.screens.gamedetail.delegates.RommUploadDelegate
import com.nendo.argosy.ui.screens.gamedetail.delegates.RommUploadPhase
import com.nendo.argosy.ui.screens.gamedetail.delegates.RommUploadRow
import com.nendo.argosy.ui.screens.gamedetail.delegates.RommUploadState
import com.nendo.argosy.ui.theme.Dimens
import com.nendo.argosy.util.formatBytes

@Composable
fun RommUploadModal(
    state: RommUploadState,
    delegate: RommUploadDelegate) {
    val inputDispatcher = LocalInputDispatcher.current
    val currentState = rememberUpdatedState(state)

    val inputHandler = remember(delegate) {
        object : InputHandler {
            private val confirming: Boolean get() = currentState.value.overwriteConfirmFocus != null

            override fun onUp(): InputResult {
                if (!confirming) delegate.moveFocus(-1)
                return InputResult.HANDLED
            }
            override fun onDown(): InputResult {
                if (!confirming) delegate.moveFocus(1)
                return InputResult.HANDLED
            }
            override fun onLeft(): InputResult = horizontal(-1)
            override fun onRight(): InputResult = horizontal(1)

            private fun horizontal(direction: Int): InputResult {
                if (confirming) {
                    delegate.moveOverwriteFocus(direction)
                    return InputResult.HANDLED
                }
                return if (delegate.cycleFocused(direction)) {
                    InputResult.handled(SoundType.TOGGLE)
                } else {
                    InputResult.handled(SoundType.BOUNDARY)
                }
            }

            override fun onConfirm(): InputResult {
                if (confirming) {
                    delegate.confirmOverwriteFocus()
                    return InputResult.handled(SoundType.SELECT)
                }
                return InputResult.handled(delegate.confirmFocused())
            }
            override fun onBack(): InputResult {
                if (confirming) delegate.dismissOverwrite() else delegate.dismiss()
                return InputResult.handled(SoundType.CLOSE_MODAL)
            }
            override fun onContextMenu(): InputResult = InputResult.HANDLED
            override fun onSecondaryAction(): InputResult = InputResult.HANDLED
            override fun onSelect(): InputResult = InputResult.HANDLED
            override fun onPrevSection(): InputResult = InputResult.HANDLED
            override fun onNextSection(): InputResult = InputResult.HANDLED
            override fun onMenu(): InputResult = InputResult.HANDLED
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, inputHandler) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) inputDispatcher.pushModal(inputHandler)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            inputDispatcher.removeModal(inputHandler)
        }
    }

    Modal(
        title = stringResource(R.string.upload_detail_title),
        subtitle = state.gameTitle.ifBlank { null },
        onDismiss = { delegate.dismiss() },
        footerHints = listOf(InputButton.DPAD_HORIZONTAL to stringResource(R.string.upload_detail_footer_change)),
        onFooterHintClick = { button ->
            if (button == InputButton.DPAD_HORIZONTAL) inputHandler.onRight()
        }
    ) {
        OptionItem(
            label = stringResource(R.string.upload_detail_server),
            value = state.server?.name,
            isFocused = state.focusedRow == RommUploadRow.SERVER,
            onClick = { delegate.activate(RommUploadRow.SERVER) }
        )
        OptionItem(
            label = stringResource(R.string.upload_settings_duplicates_title),
            value = stringResource(state.policy.labelRes),
            isFocused = state.focusedRow == RommUploadRow.POLICY,
            onClick = { delegate.activate(RommUploadRow.POLICY) }
        )
        RommUploadSummary(state)
        OptionItem(
            icon = Icons.Default.CloudUpload,
            label = stringResource(R.string.upload_detail_start),
            isFocused = state.focusedRow == RommUploadRow.UPLOAD,
            isEnabled = state.canUpload,
            onClick = { delegate.activate(RommUploadRow.UPLOAD) }
        )
        OptionItem(
            label = stringResource(R.string.upload_detail_cancel),
            isFocused = state.focusedRow == RommUploadRow.CANCEL,
            onClick = { delegate.activate(RommUploadRow.CANCEL) }
        )
    }

    val overwriteFocus = state.overwriteConfirmFocus
    if (overwriteFocus != null) {
        ArgosyConfirmModal(
            title = stringResource(R.string.upload_detail_overwrite_title),
            message = stringResource(
                R.string.upload_detail_overwrite_message,
                state.gameTitle,
                state.server?.name.orEmpty()
            ),
            confirmLabel = stringResource(R.string.upload_detail_overwrite_confirm),
            onConfirm = { delegate.confirmOverwrite() },
            onDismiss = { delegate.dismissOverwrite() },
            focusedIndex = overwriteFocus,
            destructive = true
        )
    }
}

@Composable
private fun RommUploadSummary(state: RommUploadState) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.spacingMd, vertical = Dimens.spacingSm)
    ) {
        val game = state.game
        when {
            state.phase == RommUploadPhase.PLANNING -> SummaryNote(stringResource(R.string.upload_detail_planning))
            state.phase == RommUploadPhase.UNAVAILABLE || game == null ->
                SummaryNote(stringResource(R.string.upload_detail_unavailable), warning = true)
            else -> {
                SummaryLine(stringResource(R.string.upload_detail_platform), state.platformName)
                SummaryLine(
                    stringResource(R.string.upload_detail_destination),
                    game.remoteGameDirectory ?: stringResource(R.string.upload_detail_destination_unknown)
                )
                SummaryLine(
                    stringResource(R.string.upload_detail_files),
                    stringResource(R.string.upload_detail_files_value, game.files.size, formatBytes(game.totalBytes))
                )
                if (!game.isPlaceable) {
                    SummaryNote(stringResource(R.string.upload_detail_no_folder), warning = true)
                }
                if (state.missingFiles.isNotEmpty()) {
                    SummaryNote(
                        stringResource(R.string.upload_detail_missing_files, state.missingFiles.joinToString()),
                        warning = true
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SummaryNote(text: String, warning: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
