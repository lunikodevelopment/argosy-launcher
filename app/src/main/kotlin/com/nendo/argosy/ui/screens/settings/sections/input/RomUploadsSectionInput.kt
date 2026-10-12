package com.nendo.argosy.ui.screens.settings.sections.input

import com.nendo.argosy.core.input.SoundType
import com.nendo.argosy.ui.input.InputHandler
import com.nendo.argosy.ui.input.InputResult
import com.nendo.argosy.ui.screens.settings.SettingsSection
import com.nendo.argosy.ui.screens.settings.SettingsViewModel
import com.nendo.argosy.ui.screens.settings.routeRomUploadProfileCycle
import com.nendo.argosy.ui.screens.settings.routeRomUploadsCycle
import com.nendo.argosy.ui.screens.settings.sections.romUploadProfileItemAtFocusIndex
import com.nendo.argosy.ui.screens.settings.sections.romUploadsItemAtFocusIndex

internal class RomUploadsSectionInput(
    private val viewModel: SettingsViewModel
) : InputHandler {

    override fun onLeft(): InputResult = cycle(-1)

    override fun onRight(): InputResult = cycle(1)

    private fun cycle(direction: Int): InputResult {
        val state = viewModel.uiState.value
        val handled = when (state.currentSection) {
            SettingsSection.ROM_UPLOADS -> romUploadsItemAtFocusIndex(state.focusedIndex, state.romUpload)
                ?.let { routeRomUploadsCycle(viewModel, it, direction) } == true
            SettingsSection.ROM_UPLOAD_PROFILE -> romUploadProfileItemAtFocusIndex(state.focusedIndex, state.romUpload)
                ?.let { routeRomUploadProfileCycle(viewModel, it, direction) } == true
            else -> false
        }
        return if (handled) InputResult.handled(SoundType.TOGGLE) else InputResult.UNHANDLED
    }
}
