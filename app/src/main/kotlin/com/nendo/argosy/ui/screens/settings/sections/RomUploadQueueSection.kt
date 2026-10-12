package com.nendo.argosy.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nendo.argosy.R
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.ui.common.labelRes
import com.nendo.argosy.ui.common.queueNoticeRes
import com.nendo.argosy.ui.components.ActionPreference
import com.nendo.argosy.ui.components.Modal
import com.nendo.argosy.ui.primitives.ArgosyProgressBar
import com.nendo.argosy.ui.screens.gamedetail.components.OptionItem
import com.nendo.argosy.ui.screens.settings.RomUploadJobAction
import com.nendo.argosy.ui.screens.settings.RomUploadJobUi
import com.nendo.argosy.ui.screens.settings.RomUploadState
import com.nendo.argosy.ui.screens.settings.SettingsUiState
import com.nendo.argosy.ui.screens.settings.SettingsViewModel
import com.nendo.argosy.ui.screens.settings.components.SectionHeader
import com.nendo.argosy.ui.screens.settings.components.SectionPaneLayout
import com.nendo.argosy.ui.screens.settings.menu.SettingsLayout
import com.nendo.argosy.ui.screens.settings.routeRomUploadQueueActivate
import com.nendo.argosy.ui.theme.Dimens
import com.nendo.argosy.util.formatBytes

internal sealed class RomUploadQueueItem(val key: String, val section: String?) {
    val isFocusable: Boolean get() = this !is Header && this !is Notice && this !is Empty

    class Header(key: String, section: String, val titleRes: Int) : RomUploadQueueItem(key, section)

    data class Notice(val messageRes: Int) : RomUploadQueueItem("notice", null)
    data object Empty : RomUploadQueueItem("empty", null)
    data class Job(val jobId: String, val finished: Boolean) :
        RomUploadQueueItem("job-$jobId", if (finished) SECTION_HISTORY else SECTION_ACTIVE)
    data object PauseAll : RomUploadQueueItem("pauseAll", SECTION_ACTIONS)
    data object ResumeAll : RomUploadQueueItem("resumeAll", SECTION_ACTIONS)
    data object ClearFinished : RomUploadQueueItem("clearFinished", SECTION_ACTIONS)

    companion object {
        const val SECTION_ACTIVE = "active"
        const val SECTION_HISTORY = "history"
        const val SECTION_ACTIONS = "actions"
    }
}

internal fun buildRomUploadQueueItems(state: RomUploadState): List<RomUploadQueueItem> = buildList {
    val open = state.openJobList
    val finished = state.finishedJobList
    state.hold.queueNoticeRes?.takeIf { open.isNotEmpty() }?.let { add(RomUploadQueueItem.Notice(it)) }
    if (open.isEmpty() && finished.isEmpty()) add(RomUploadQueueItem.Empty)
    add(RomUploadQueueItem.Header("activeHeader", RomUploadQueueItem.SECTION_ACTIVE, R.string.upload_queue_section_active))
    open.forEach { add(RomUploadQueueItem.Job(it.id, finished = false)) }
    add(RomUploadQueueItem.Header("historyHeader", RomUploadQueueItem.SECTION_HISTORY, R.string.upload_queue_section_history))
    finished.forEach { add(RomUploadQueueItem.Job(it.id, finished = true)) }
    add(RomUploadQueueItem.Header("actionsHeader", RomUploadQueueItem.SECTION_ACTIONS, R.string.upload_queue_section_actions))
    if (state.hasRunningJobs) add(RomUploadQueueItem.PauseAll)
    if (state.hasPausedJobs) add(RomUploadQueueItem.ResumeAll)
    if (finished.isNotEmpty()) add(RomUploadQueueItem.ClearFinished)
}

private fun romUploadQueueLayout(state: RomUploadState) = SettingsLayout<RomUploadQueueItem, Unit>(
    allItems = buildRomUploadQueueItems(state),
    isFocusable = { it.isFocusable },
    visibleWhen = { _, _ -> true },
    sectionOf = { it.section },
    sectionTitleRes = {
        when (it) {
            RomUploadQueueItem.SECTION_ACTIVE -> R.string.upload_queue_section_active
            RomUploadQueueItem.SECTION_HISTORY -> R.string.upload_queue_section_history
            RomUploadQueueItem.SECTION_ACTIONS -> R.string.upload_queue_section_actions
            else -> null
        }
    }
)

internal fun romUploadQueueItemAtFocusIndex(index: Int, state: RomUploadState): RomUploadQueueItem? =
    romUploadQueueLayout(state).itemAtFocusIndex(index, Unit)

internal fun romUploadQueueMaxFocusIndex(state: RomUploadState): Int =
    romUploadQueueLayout(state).maxFocusIndex(Unit)

@Composable
fun RomUploadQueueSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val state = uiState.romUpload
    val context = LocalContext.current
    val layout = remember(state.jobs, state.hold) { romUploadQueueLayout(state) }
    val items = remember(layout) { layout.visibleItems(Unit) }
    val sections = remember(layout, context) { layout.buildSections(Unit, context) }
    val jobsById = remember(state.jobs) { state.jobs.associateBy { it.id } }

    fun isFocused(item: RomUploadQueueItem): Boolean = uiState.focusedIndex == layout.focusIndexOf(item, Unit)

    fun activate(item: RomUploadQueueItem) {
        viewModel.setFocusIndex(layout.focusIndexOf(item, Unit))
        routeRomUploadQueueActivate(viewModel, item)
    }

    SectionPaneLayout(
        items = items,
        sections = sections,
        focusedIndex = uiState.focusedIndex,
        focusToListIndex = { layout.focusToListIndex(it, Unit) },
        itemKey = { it.key },
        isNavItem = { false },
        isHeader = { it is RomUploadQueueItem.Header },
        onSectionTap = { viewModel.setFocusIndex(it.focusStartIndex) },
        modifier = Modifier.fillMaxSize().padding(Dimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
    ) { item ->
        when (item) {
            is RomUploadQueueItem.Header -> {
                if (item.section != RomUploadQueueItem.SECTION_ACTIVE) {
                    Spacer(modifier = Modifier.height(Dimens.spacingSm))
                }
                SectionHeader(stringResource(item.titleRes))
            }
            is RomUploadQueueItem.Notice -> QueueNote(stringResource(item.messageRes))
            RomUploadQueueItem.Empty -> QueueNote(stringResource(R.string.upload_queue_empty))
            is RomUploadQueueItem.Job -> jobsById[item.jobId]?.let { job ->
                QueueJobRow(job = job, isFocused = isFocused(item), onClick = { activate(item) })
            }
            RomUploadQueueItem.PauseAll -> ActionPreference(
                icon = Icons.Default.Pause,
                title = stringResource(R.string.upload_queue_pause_all_title),
                subtitle = stringResource(R.string.upload_queue_pause_all_subtitle),
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadQueueItem.ResumeAll -> ActionPreference(
                icon = Icons.Default.PlayArrow,
                title = stringResource(R.string.upload_queue_resume_all_title),
                subtitle = stringResource(R.string.upload_queue_resume_all_subtitle),
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadQueueItem.ClearFinished -> ActionPreference(
                icon = Icons.Default.DeleteSweep,
                title = stringResource(R.string.upload_queue_clear_title),
                subtitle = stringResource(R.string.upload_queue_clear_subtitle),
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
        }
    }

    RomUploadJobMenuModal(state, viewModel)
}

@Composable
private fun QueueJobRow(job: RomUploadJobUi, isFocused: Boolean, onClick: () -> Unit) {
    val status = job.errorKind?.takeIf { job.state == UploadItemState.FAILED }?.let { stringResource(it.labelRes) }
        ?: stringResource(job.state.labelRes)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
        ActionPreference(
            icon = job.stateIcon,
            title = job.title,
            subtitle = if (job.serverName.isBlank()) {
                status
            } else {
                stringResource(R.string.upload_queue_job_subtitle, job.serverName, status)
            },
            trailingText = if (job.state.isTerminal) {
                formatBytes(job.bytesTotal)
            } else {
                stringResource(R.string.upload_queue_job_progress, formatBytes(job.bytesDone), formatBytes(job.bytesTotal))
            },
            isDangerous = job.state == UploadItemState.FAILED,
            isFocused = isFocused,
            onClick = onClick
        )
        if (!job.state.isTerminal) {
            ArgosyProgressBar(
                progress = job.progress,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spacingMd)
            )
        }
    }
}

private val RomUploadJobUi.stateIcon: ImageVector
    get() = when (state) {
        UploadItemState.COMPLETED, UploadItemState.SKIPPED -> Icons.Default.CheckCircle
        UploadItemState.FAILED -> Icons.Default.ErrorOutline
        UploadItemState.CANCELLED -> Icons.Default.Cancel
        UploadItemState.PAUSED -> Icons.Default.Pause
        else -> Icons.Default.CloudUpload
    }

@Composable
private fun QueueNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Dimens.spacingSm)
    )
}

@Composable
private fun RomUploadJobMenuModal(state: RomUploadState, viewModel: SettingsViewModel) {
    val menu = state.jobMenu ?: return
    val job = state.jobs.firstOrNull { it.id == menu.jobId } ?: return
    val delegate = viewModel.romUploadDelegate
    Modal(
        title = job.title,
        subtitle = stringResource(job.state.labelRes),
        onDismiss = { delegate.closeJobMenu() }
    ) {
        job.actions.forEachIndexed { index, action ->
            OptionItem(
                icon = action.icon,
                label = stringResource(action.labelRes),
                isFocused = menu.focusIndex == index,
                isDangerous = action == RomUploadJobAction.CANCEL,
                onClick = { delegate.runJobAction(job.id, action) }
            )
        }
    }
}

private val RomUploadJobAction.icon: ImageVector
    get() = when (this) {
        RomUploadJobAction.PAUSE -> Icons.Default.Pause
        RomUploadJobAction.RESUME -> Icons.Default.PlayArrow
        RomUploadJobAction.RETRY -> Icons.Default.Refresh
        RomUploadJobAction.CANCEL -> Icons.Default.Cancel
    }

private val RomUploadJobAction.labelRes: Int
    get() = when (this) {
        RomUploadJobAction.PAUSE -> R.string.upload_queue_action_pause
        RomUploadJobAction.RESUME -> R.string.upload_queue_action_resume
        RomUploadJobAction.RETRY -> R.string.upload_queue_action_retry
        RomUploadJobAction.CANCEL -> R.string.upload_queue_action_cancel
    }
