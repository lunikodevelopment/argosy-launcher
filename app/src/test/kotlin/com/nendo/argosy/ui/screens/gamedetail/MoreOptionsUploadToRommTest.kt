package com.nendo.argosy.ui.screens.gamedetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreOptionsUploadToRommTest {

    @Test
    fun `a downloaded emulated game offers upload once a server is set up`() {
        val options = buildMoreOptions(MoreOptionsContext(isDownloaded = true, canUploadToRomm = true))

        assertEquals(1, options.count { it == MoreOptionAction.UploadToRomm })
    }

    @Test
    fun `upload stays hidden without a server`() {
        val options = buildMoreOptions(MoreOptionsContext(isDownloaded = true, isRommGame = true))

        assertFalse(options.contains(MoreOptionAction.UploadToRomm))
    }

    @Test
    fun `upload needs the game on the device`() {
        val options = buildMoreOptions(MoreOptionsContext(isDownloaded = false, canUploadToRomm = true))

        assertFalse(options.contains(MoreOptionAction.UploadToRomm))
    }

    @Test
    fun `steam games and android apps never offer upload`() {
        val contexts = listOf(
            MoreOptionsContext(isDownloaded = true, isSteamGame = true, canUploadToRomm = true),
            MoreOptionsContext(isDownloaded = true, isAndroidApp = true, canUploadToRomm = true)
        )

        contexts.forEach { ctx ->
            assertFalse(ctx.toString(), buildMoreOptions(ctx).contains(MoreOptionAction.UploadToRomm))
        }
    }

    @Test
    fun `upload sits before the destructive tail`() {
        val options = buildMoreOptions(MoreOptionsContext(isDownloaded = true, canUploadToRomm = true))

        assertTrue(options.indexOf(MoreOptionAction.UploadToRomm) < options.indexOf(MoreOptionAction.Delete))
    }
}
