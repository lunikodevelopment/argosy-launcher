package com.nendo.argosy.domain.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFileGrouperTest {

    private fun names(group: FileGroup) = group.members.map { it.name }.toSet()

    @Test
    fun `a cue sheet claims the tracks it names`() {
        val tree = FakeLocalTree()
            .file("/r/psx/Crash.cue", "FILE \"Crash (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\nFILE \"Crash (Track 2).bin\" BINARY\n")
            .file("/r/psx/Crash (Track 1).bin")
            .file("/r/psx/Crash (Track 2).bin")
            .file("/r/psx/Spyro.chd")
        val groups = GameFileGrouper(tree).group(tree.list("/r/psx")!!)
        assertEquals(2, groups.size)
        val crash = groups.first { it.primary.name == "Crash.cue" }
        assertEquals(setOf("Crash.cue", "Crash (Track 1).bin", "Crash (Track 2).bin"), names(crash))
        assertTrue(crash.isSet)
    }

    @Test
    fun `a playlist claims its discs and the tracks of listed cue sheets`() {
        val tree = FakeLocalTree()
            .file("/r/psx/FF7.m3u", "#EXTM3U\nFF7 (Disc 1).cue\nFF7 (Disc 2).chd\n")
            .file("/r/psx/FF7 (Disc 1).cue", "FILE \"FF7 (Disc 1).bin\" BINARY\n")
            .file("/r/psx/FF7 (Disc 1).bin")
            .file("/r/psx/FF7 (Disc 2).chd")
        val groups = GameFileGrouper(tree).group(tree.list("/r/psx")!!)
        assertEquals(1, groups.size)
        assertEquals(
            setOf("FF7.m3u", "FF7 (Disc 1).cue", "FF7 (Disc 1).bin", "FF7 (Disc 2).chd"),
            names(groups.single())
        )
    }

    @Test
    fun `a gdi table claims its tracks including quoted names`() {
        val gdi = "3\n1 0 4 2352 track01.bin 0\n2 756 0 2352 \"track 02.raw\" 0\n3 45000 4 2352 track03.bin 0\n"
        val tree = FakeLocalTree()
            .file("/r/dc/Game.gdi", gdi)
            .file("/r/dc/track01.bin").file("/r/dc/track 02.raw").file("/r/dc/track03.bin")
        val group = GameFileGrouper(tree).group(tree.list("/r/dc")!!).single()
        assertEquals(setOf("Game.gdi", "track01.bin", "track 02.raw", "track03.bin"), names(group))
    }

    @Test
    fun `missing references are reported and not invented`() {
        val tree = FakeLocalTree().file("/r/psx/Game.cue", "FILE \"Game.bin\" BINARY\n")
        val group = GameFileGrouper(tree).group(tree.list("/r/psx")!!).single()
        assertEquals(listOf("Game.bin"), group.missingReferences)
        assertEquals(setOf("Game.cue"), names(group))
    }

    @Test
    fun `selecting a track brings its whole cue set`() {
        val tree = FakeLocalTree()
            .file("/r/psx/Game.cue", "FILE \"Game.bin\" BINARY\n")
            .file("/r/psx/Game.bin")
            .file("/r/psx/Other.bin")
        val siblings = tree.list("/r/psx")!!
        val group = GameFileGrouper(tree).groupContaining(siblings.first { it.name == "Game.bin" }, siblings)
        assertEquals(setOf("Game.cue", "Game.bin"), names(group))
    }

    @Test
    fun `clonecd descriptors pair with img and sub by base name`() {
        val tree = FakeLocalTree().file("/r/x/Game.ccd").file("/r/x/Game.img").file("/r/x/Game.sub")
        val group = GameFileGrouper(tree).group(tree.list("/r/x")!!).single()
        assertEquals(setOf("Game.ccd", "Game.img", "Game.sub"), names(group))
    }

    @Test
    fun `hidden files are never part of a game`() {
        val tree = FakeLocalTree().file("/r/gba/.DS_Store").file("/r/gba/Game.gba")
        val groups = GameFileGrouper(tree).group(tree.list("/r/gba")!!)
        assertEquals(listOf("Game.gba"), groups.map { it.primary.name })
    }
}
