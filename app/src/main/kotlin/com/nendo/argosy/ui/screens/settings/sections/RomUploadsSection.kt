package com.nendo.argosy.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nendo.argosy.R
import com.nendo.argosy.data.sftpserver.SftpServerController
import com.nendo.argosy.data.sftpserver.SftpServerFailure
import com.nendo.argosy.ui.screens.settings.SftpServerUiState
import com.nendo.argosy.ui.common.labelRes
import com.nendo.argosy.ui.components.ActionPreference
import com.nendo.argosy.ui.components.CyclePreference
import com.nendo.argosy.ui.components.NavigationPreference
import com.nendo.argosy.ui.components.SwitchPreference
import com.nendo.argosy.ui.screens.settings.RomUploadState
import com.nendo.argosy.ui.screens.settings.SettingsUiState
import com.nendo.argosy.ui.screens.settings.SettingsViewModel
import com.nendo.argosy.ui.screens.settings.components.SectionHeader
import com.nendo.argosy.ui.screens.settings.components.SectionPaneLayout
import com.nendo.argosy.ui.screens.settings.menu.SettingsLayout
import com.nendo.argosy.ui.screens.settings.routeRomUploadsActivate
import com.nendo.argosy.ui.screens.settings.routeRomUploadsCycle
import com.nendo.argosy.ui.theme.Dimens

internal sealed class RomUploadItem(val key: String, val section: String) {
    val isFocusable: Boolean get() = this !is Header

    class Header(key: String, section: String, val titleRes: Int) : RomUploadItem(key, section)

    data object Queue : RomUploadItem("queue", SECTION_QUEUE)
    data object ImportLocal : RomUploadItem("importLocal", SECTION_QUEUE)
    data object QuickFolder : RomUploadItem("quickFolder", SECTION_QUEUE)
    data object QuickUpload : RomUploadItem("quickUpload", SECTION_QUEUE)
    data object ImportMissing : RomUploadItem("importMissing", SECTION_QUEUE)
    data class Profile(val profileId: String) : RomUploadItem("profile-$profileId", SECTION_SERVERS)
    data object AddProfile : RomUploadItem("addProfile", SECTION_SERVERS)
    data object DuplicatePolicy : RomUploadItem("duplicatePolicy", SECTION_TRANSFERS)
    data object Concurrency : RomUploadItem("concurrency", SECTION_TRANSFERS)
    data object WifiOnly : RomUploadItem("wifiOnly", SECTION_TRANSFERS)
    data object ChargingOnly : RomUploadItem("chargingOnly", SECTION_TRANSFERS)
    data object Bandwidth : RomUploadItem("bandwidth", SECTION_TRANSFERS)
    data object InGameLimit : RomUploadItem("inGameLimit", SECTION_TRANSFERS)
    data object NotifyOnCompletion : RomUploadItem("notifyOnCompletion", SECTION_TRANSFERS)
    data object ServerToggle : RomUploadItem("serverToggle", SECTION_SERVER)
    data object ServerPort : RomUploadItem("serverPort", SECTION_SERVER)
    data object ServerWritable : RomUploadItem("serverWritable", SECTION_SERVER)
    data object ServerDetails : RomUploadItem("serverDetails", SECTION_SERVER)
    data object ServerRegenerate : RomUploadItem("serverRegenerate", SECTION_SERVER)
    data object ScanAfterUpload : RomUploadItem("scanAfterUpload", SECTION_SCAN)
    data object ScanToken : RomUploadItem("scanToken", SECTION_SCAN)
    data object RemoveScanToken : RomUploadItem("removeScanToken", SECTION_SCAN)

    companion object {
        const val SECTION_QUEUE = "queue"
        const val SECTION_SERVERS = "servers"
        const val SECTION_TRANSFERS = "transfers"
        const val SECTION_SCAN = "scan"
        const val SECTION_SERVER = "server"
    }
}

internal fun buildRomUploadItems(state: RomUploadState): List<RomUploadItem> = buildList {
    add(RomUploadItem.Header("queueHeader", RomUploadItem.SECTION_QUEUE, R.string.upload_settings_section_queue))
    add(RomUploadItem.Queue)
    add(RomUploadItem.ImportLocal)
    add(RomUploadItem.QuickFolder)
    add(RomUploadItem.QuickUpload)
    add(RomUploadItem.ImportMissing)
    add(RomUploadItem.Header("serversHeader", RomUploadItem.SECTION_SERVERS, R.string.upload_settings_section_servers))
    state.profiles.forEach { add(RomUploadItem.Profile(it.id)) }
    add(RomUploadItem.AddProfile)
    add(RomUploadItem.Header("transfersHeader", RomUploadItem.SECTION_TRANSFERS, R.string.upload_settings_section_transfers))
    add(RomUploadItem.DuplicatePolicy)
    add(RomUploadItem.Concurrency)
    add(RomUploadItem.WifiOnly)
    add(RomUploadItem.ChargingOnly)
    add(RomUploadItem.Bandwidth)
    add(RomUploadItem.InGameLimit)
    add(RomUploadItem.NotifyOnCompletion)
    add(RomUploadItem.Header("scanHeader", RomUploadItem.SECTION_SCAN, R.string.upload_settings_section_scan))
    add(RomUploadItem.ScanAfterUpload)
    add(RomUploadItem.ScanToken)
    if (state.hasScanToken) add(RomUploadItem.RemoveScanToken)
    add(RomUploadItem.Header("serverHeader", RomUploadItem.SECTION_SERVER, R.string.sftp_settings_section))
    add(RomUploadItem.ServerToggle)
    add(RomUploadItem.ServerPort)
    add(RomUploadItem.ServerWritable)
    add(RomUploadItem.ServerDetails)
    add(RomUploadItem.ServerRegenerate)
}

private fun romUploadsLayout(state: RomUploadState) = SettingsLayout<RomUploadItem, Unit>(
    allItems = buildRomUploadItems(state),
    isFocusable = { it.isFocusable },
    visibleWhen = { _, _ -> true },
    sectionOf = { it.section },
    sectionTitleRes = {
        when (it) {
            RomUploadItem.SECTION_QUEUE -> R.string.upload_settings_section_queue
            RomUploadItem.SECTION_SERVERS -> R.string.upload_settings_section_servers
            RomUploadItem.SECTION_TRANSFERS -> R.string.upload_settings_section_transfers
            RomUploadItem.SECTION_SCAN -> R.string.upload_settings_section_scan
            RomUploadItem.SECTION_SERVER -> R.string.sftp_settings_section
            else -> null
        }
    }
)

internal fun romUploadsItemAtFocusIndex(index: Int, state: RomUploadState): RomUploadItem? =
    romUploadsLayout(state).itemAtFocusIndex(index, Unit)

internal fun romUploadsMaxFocusIndex(state: RomUploadState): Int =
    romUploadsLayout(state).maxFocusIndex(Unit)

@Composable
internal fun speedLimitLabel(kibps: Int): String = when {
    kibps <= 0 -> stringResource(R.string.upload_settings_speed_unlimited)
    kibps % KIB_PER_MIB == 0 -> stringResource(R.string.upload_settings_speed_mibps, kibps / KIB_PER_MIB)
    else -> stringResource(R.string.upload_settings_speed_kibps, kibps)
}

private const val KIB_PER_MIB = 1024

@Composable
private fun sftpDetailsText(server: SftpServerUiState): String = when {
    server.failure == SftpServerFailure.NO_ROM_FOLDER -> stringResource(R.string.sftp_settings_error_no_rom_folder)
    server.failure != null -> stringResource(R.string.sftp_settings_error_start_failed)
    server.running -> stringResource(
        R.string.sftp_settings_details_running,
        server.address ?: stringResource(R.string.sftp_notification_no_address),
        server.activePort,
        SftpServerController.USERNAME,
        server.password.orEmpty()
    )
    else -> stringResource(R.string.sftp_settings_details_stopped)
}

@Composable
fun RomUploadsSection(uiState: SettingsUiState, viewModel: SettingsViewModel) {
    val state = uiState.romUpload
    val context = LocalContext.current
    val layout = remember(state.profiles, state.hasScanToken) { romUploadsLayout(state) }
    val items = remember(layout) { layout.visibleItems(Unit) }
    val sections = remember(layout, context) { layout.buildSections(Unit, context) }

    fun isFocused(item: RomUploadItem): Boolean = uiState.focusedIndex == layout.focusIndexOf(item, Unit)

    fun activate(item: RomUploadItem) {
        viewModel.setFocusIndex(layout.focusIndexOf(item, Unit))
        routeRomUploadsActivate(viewModel, item)
    }

    fun cycle(item: RomUploadItem, direction: Int) {
        viewModel.setFocusIndex(layout.focusIndexOf(item, Unit))
        routeRomUploadsCycle(viewModel, item, direction)
    }

    val settings = state.settings
    val server = uiState.sftpServer
    SectionPaneLayout(
        items = items,
        sections = sections,
        focusedIndex = uiState.focusedIndex,
        focusToListIndex = { layout.focusToListIndex(it, Unit) },
        itemKey = { it.key },
        isNavItem = { false },
        isHeader = { it is RomUploadItem.Header },
        onSectionTap = { viewModel.setFocusIndex(it.focusStartIndex) },
        modifier = Modifier.fillMaxSize().padding(Dimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
    ) { item ->
        when (item) {
            is RomUploadItem.Header -> {
                if (item.section != RomUploadItem.SECTION_QUEUE) {
                    Spacer(modifier = Modifier.height(Dimens.spacingSm))
                }
                SectionHeader(stringResource(item.titleRes))
            }
            RomUploadItem.Queue -> NavigationPreference(
                icon = Icons.Default.CloudUpload,
                title = stringResource(R.string.upload_settings_queue_title),
                subtitle = if (state.openJobs > 0) {
                    pluralStringResource(R.plurals.upload_settings_queue_open, state.openJobs, state.openJobs)
                } else {
                    stringResource(R.string.upload_settings_queue_idle)
                },
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadItem.ImportLocal -> ActionPreference(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.upload_settings_import_title),
                subtitle = stringResource(
                    if (state.profiles.isEmpty()) {
                        R.string.upload_settings_import_needs_server
                    } else {
                        R.string.upload_settings_import_subtitle
                    }
                ),
                isFocused = isFocused(item),
                isEnabled = state.profiles.isNotEmpty(),
                onClick = { activate(item) }
            )
            RomUploadItem.QuickFolder -> ActionPreference(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.upload_settings_quick_folder_title),
                subtitle = state.quickFolder ?: stringResource(R.string.upload_settings_quick_folder_unset),
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadItem.QuickUpload -> ActionPreference(
                icon = Icons.Default.CloudUpload,
                title = stringResource(R.string.upload_settings_quick_upload_title),
                subtitle = when {
                    state.profiles.isEmpty() -> stringResource(R.string.upload_settings_import_needs_server)
                    state.quickFolder == null -> stringResource(R.string.upload_settings_quick_upload_needs_folder)
                    else -> stringResource(R.string.upload_settings_quick_upload_subtitle, state.quickFolder.substringAfterLast('/'))
                },
                isFocused = isFocused(item),
                isEnabled = state.profiles.isNotEmpty(),
                onClick = { activate(item) }
            )
            RomUploadItem.ImportMissing -> ActionPreference(
                icon = Icons.Default.CloudUpload,
                title = stringResource(R.string.upload_settings_missing_title),
                subtitle = stringResource(
                    if (state.profiles.isEmpty()) {
                        R.string.upload_settings_import_needs_server
                    } else {
                        R.string.upload_settings_missing_subtitle
                    }
                ),
                isFocused = isFocused(item),
                isEnabled = state.profiles.isNotEmpty(),
                onClick = { activate(item) }
            )
            is RomUploadItem.Profile -> {
                val profile = state.profiles.firstOrNull { it.id == item.profileId }
                if (profile != null) {
                    NavigationPreference(
                        icon = Icons.Default.Dns,
                        title = profile.name.ifBlank { profile.host },
                        subtitle = stringResource(
                            R.string.upload_settings_profile_subtitle,
                            stringResource(profile.protocol.labelRes),
                            profile.username,
                            profile.host
                        ),
                        isFocused = isFocused(item),
                        badge = if (profile.id == state.defaultProfileId) {
                            stringResource(R.string.upload_settings_profile_default_badge)
                        } else {
                            null
                        },
                        onClick = { activate(item) }
                    )
                }
            }
            RomUploadItem.AddProfile -> ActionPreference(
                icon = Icons.Default.Add,
                title = stringResource(R.string.upload_settings_add_profile_title),
                subtitle = stringResource(R.string.upload_settings_add_profile_subtitle),
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadItem.DuplicatePolicy -> CyclePreference(
                title = stringResource(R.string.upload_settings_duplicates_title),
                value = stringResource(settings.duplicatePolicy.labelRes),
                isFocused = isFocused(item),
                onClick = { cycle(item, 1) },
                onPrev = { cycle(item, -1) }
            )
            RomUploadItem.Concurrency -> CyclePreference(
                title = stringResource(R.string.upload_settings_concurrency_title),
                subtitle = stringResource(R.string.upload_settings_concurrency_subtitle),
                value = settings.maxConcurrentJobs.toString(),
                isFocused = isFocused(item),
                onClick = { cycle(item, 1) },
                onPrev = { cycle(item, -1) }
            )
            RomUploadItem.WifiOnly -> SwitchPreference(
                title = stringResource(R.string.upload_settings_wifi_only_title),
                subtitle = stringResource(R.string.upload_settings_wifi_only_subtitle),
                isEnabled = settings.wifiOnly,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadItem.ChargingOnly -> SwitchPreference(
                title = stringResource(R.string.upload_settings_charging_only_title),
                subtitle = stringResource(R.string.upload_settings_charging_only_subtitle),
                isEnabled = settings.chargingOnly,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadItem.Bandwidth -> CyclePreference(
                title = stringResource(R.string.upload_settings_bandwidth_title),
                value = speedLimitLabel(settings.bandwidthLimitKiBps),
                isFocused = isFocused(item),
                onClick = { cycle(item, 1) },
                onPrev = { cycle(item, -1) }
            )
            RomUploadItem.InGameLimit -> CyclePreference(
                title = stringResource(R.string.upload_settings_in_game_limit_title),
                value = speedLimitLabel(settings.inGameBandwidthLimitKiBps),
                isFocused = isFocused(item),
                onClick = { cycle(item, 1) },
                onPrev = { cycle(item, -1) }
            )
            RomUploadItem.NotifyOnCompletion -> SwitchPreference(
                title = stringResource(R.string.upload_settings_notify_title),
                subtitle = stringResource(R.string.upload_settings_notify_subtitle),
                isEnabled = settings.notifyOnCompletion,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadItem.ScanAfterUpload -> SwitchPreference(
                title = stringResource(R.string.upload_settings_scan_after_title),
                subtitle = stringResource(R.string.upload_settings_scan_after_subtitle),
                isEnabled = settings.scanAfterUpload,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadItem.ScanToken -> ActionPreference(
                icon = Icons.Default.Key,
                title = stringResource(R.string.upload_settings_scan_token_title),
                subtitle = if (state.hasScanToken) {
                    stringResource(R.string.upload_settings_scan_token_set)
                } else {
                    stringResource(R.string.upload_settings_scan_token_missing)
                },
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadItem.ServerToggle -> SwitchPreference(
                title = stringResource(R.string.sftp_settings_toggle_title),
                subtitle = stringResource(
                    if (server.running) R.string.sftp_settings_toggle_on else R.string.sftp_settings_toggle_off
                ),
                isEnabled = server.running,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadItem.ServerPort -> CyclePreference(
                title = stringResource(R.string.sftp_settings_port_title),
                value = server.port.toString(),
                isFocused = isFocused(item),
                onClick = { cycle(item, 1) },
                onPrev = { cycle(item, -1) }
            )
            RomUploadItem.ServerWritable -> SwitchPreference(
                title = stringResource(R.string.sftp_settings_writable_title),
                subtitle = stringResource(R.string.sftp_settings_writable_subtitle),
                isEnabled = server.writable,
                isFocused = isFocused(item),
                onToggle = { activate(item) }
            )
            RomUploadItem.ServerDetails -> ActionPreference(
                icon = Icons.Default.Dns,
                title = stringResource(R.string.sftp_settings_details_title),
                subtitle = sftpDetailsText(server),
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadItem.ServerRegenerate -> ActionPreference(
                icon = Icons.Default.Key,
                title = stringResource(R.string.sftp_settings_regenerate_title),
                subtitle = stringResource(R.string.sftp_settings_regenerate_subtitle),
                isFocused = isFocused(item),
                onClick = { activate(item) }
            )
            RomUploadItem.RemoveScanToken -> ActionPreference(
                icon = Icons.Default.LinkOff,
                title = stringResource(R.string.upload_settings_scan_token_remove_title),
                subtitle = stringResource(R.string.upload_settings_scan_token_remove_subtitle),
                isFocused = isFocused(item),
                isDangerous = true,
                onClick = { activate(item) }
            )
        }
    }

    RomUploadTextEntryModal(state, viewModel)
    RomUploadImportModal(uiState.romUploadImport, viewModel.romUploadImportDelegate)
}
