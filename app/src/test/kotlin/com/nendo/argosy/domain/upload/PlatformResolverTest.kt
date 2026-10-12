package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.PlatformCertainty
import com.nendo.argosy.domain.model.upload.PlatformMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformResolverTest {

    private val catalog = FakePlatformCatalog()

    @Test
    fun `user mapping wins over the folder name and metadata`() {
        val resolver = PlatformResolver(catalog, mapOf("/storage/emulated/0/Games/handheld" to "nds"))
        val match = resolver.resolve("/storage/emulated/0/Games/handheld/sub", listOf("x.gba"), knownGameSlug = "gba")
        assertEquals(PlatformMatch("nds", PlatformCertainty.USER_MAPPING), match)
    }

    @Test
    fun `game metadata wins over the folder name`() {
        val resolver = PlatformResolver(catalog, emptyMap())
        val match = resolver.resolve("/storage/emulated/0/ROMs/snes", listOf("x.gba"), knownGameSlug = "gameboyadvance")
        assertEquals(PlatformMatch("gba", PlatformCertainty.GAME_METADATA), match)
    }

    @Test
    fun `a recognised folder name identifies the platform`() {
        val resolver = PlatformResolver(catalog, emptyMap())
        val match = resolver.resolve("/storage/emulated/0/ROMs/psx/Final Fantasy VII", listOf("Disc 1.chd"))
        assertEquals(PlatformMatch("psx", PlatformCertainty.SOURCE_FOLDER), match)
    }

    @Test
    fun `an unambiguous extension identifies the platform but still asks for confirmation`() {
        val resolver = PlatformResolver(catalog, emptyMap())
        val match = resolver.resolve("/storage/emulated/0/Download", listOf("Pokemon Emerald.gba"))
        assertEquals(PlatformMatch("gba", PlatformCertainty.EXTENSION), match)
        assertTrue(match.needsConfirmation)
    }

    @Test
    fun `shared extensions never decide the platform`() {
        val resolver = PlatformResolver(catalog, emptyMap())
        assertEquals(PlatformMatch.UNKNOWN, resolver.resolve("/sdcard/Download", listOf("game.iso")))
        assertEquals(PlatformMatch.UNKNOWN, resolver.resolve("/sdcard/Download", listOf("game.pbp")))
        assertEquals(PlatformMatch.UNKNOWN, resolver.resolve("/sdcard/Download", listOf("game.zip", "game.chd")))
    }

    @Test
    fun `the longest local mapping prefix wins`() {
        val resolver = PlatformResolver(
            catalog,
            mapOf("/sdcard/ROMs" to "gba", "/sdcard/ROMs/psx" to "psx")
        )
        assertEquals("psx", resolver.resolve("/sdcard/ROMs/psx/game", listOf("a.bin")).argosySlug)
        assertEquals("gba", resolver.resolve("/sdcard/ROMs/other", listOf("a.bin")).argosySlug)
    }
}
