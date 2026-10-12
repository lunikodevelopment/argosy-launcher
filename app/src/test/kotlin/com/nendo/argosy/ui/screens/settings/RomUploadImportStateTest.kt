package com.nendo.argosy.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomUploadImportStateTest {

    private fun ready(confident: Int, uncertain: Int, include: Boolean) = RomUploadImportState(
        visible = true,
        phase = RomUploadImportPhase.READY,
        confidentGames = confident,
        uncertainGames = uncertain,
        includeUncertain = include
    )

    @Test
    fun `uncertain games are left out until the user includes them`() {
        assertEquals(3, ready(3, 2, include = false).uploadCount)
        assertEquals(5, ready(3, 2, include = true).uploadCount)
    }

    @Test
    fun `upload needs a finished plan with something to send`() {
        assertTrue(ready(1, 0, include = false).canUpload)
        assertFalse(ready(0, 2, include = false).canUpload)
        assertTrue(ready(0, 2, include = true).canUpload)
        assertFalse(RomUploadImportState(visible = true).canUpload)
    }

    @Test
    fun `either the browser or the dialog counts as open`() {
        assertTrue(RomUploadImportState(browserOpen = true).isOpen)
        assertTrue(RomUploadImportState(visible = true).isOpen)
        assertFalse(RomUploadImportState().isOpen)
    }
}
