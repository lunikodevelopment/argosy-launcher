package com.nendo.argosy.ui.screens.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nendo.argosy.R
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

    data class Profile(val profileId: String) : RomUploadItem("profile-$profileId", SECTION_SERVERS)
    data object AddProfile : RomUploadItem("addProfile", SECTION_SERVERS)
    data object DuplicatePolicy : RomUploadItem("duplicatePolicy", SECTION_TRANSFERS)
    data object Concurrency : RomUploadItem("concurrency", SECTION_TRANSFERS)
    data object WifiOnly : RomUploadItem("wifiOnly", SECTION_TRANSFERS)
    data object ChargingOnly : RomUploadItem("chargingOnly", SECTION_TRANSFERS)
    data object Bandwidth : RomUploadItem("bandwidth", SECTION_TRANSFERS)
    data object InGameLimit : RomUploadItem("inGameLimit", SECTION_TRANSFERS)
    data object NotifyOnCompletion : RomUploadItem("notifyOnCompletion", SECTION_TRANSFERS)
    data object ScanAfterUpload : RomUploadItem("scanAfterUpload", SECTION_SCAN)
    data object ScanToken : RomUploadItem("scanToken", SECTION_SCAN)
    data object RemoveScanToken : RomUploadItem("removeScanToken", SECTION_SCAN)

    companion object {
        const val SECTION_SERVERS = "servers"
        const val SECTION_TRANSFERS = "transfers"
        const val SECTION_SCAN = "scan"
    }
}

internal fun buildRomUploadItems(state: RomUploadState): List<RomUploadItem> = buildList {
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
}

private fun romUploadsLayout(state: RomUploadState) = SettingsLayout<RomUploadItem, Unit>(
    allItems = buildRomUploadItems(state),
    isFocusable = { it.isFocusable },
    visibleWhen = { _, _ -> true },
    sectionOf = { it.section },
    sectionTitleRes = {
        when (it) {
            RomUploadItem.SECTION_SERVERS -> R.string.upload_settings_section_servers
            RomUploadItem.SECTION_TRANSFERS -> R.string.upload_settings_section_transfers
            RomUploadItem.SECTION_SCAN -> R.string.upload_settings_section_scan
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
                if (item.section != RomUploadItem.SECTION_SERVERS) {
                    Spacer(modifier = Modifier.height(Dimens.spacingSm))
                }
                SectionHeader(stringResource(item.titleRes))
            }
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
}
