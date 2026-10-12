package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.GameLayout
import com.nendo.argosy.domain.model.upload.PlatformCertainty
import com.nendo.argosy.domain.model.upload.TransferProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPlannerTest {

    private val catalog = FakePlatformCatalog()

    private fun planner(
        tree: FakeLocalTree,
        template: String = RemoteLayout.DEFAULT_PLATFORM_TEMPLATE,
        protocol: TransferProtocol = TransferProtocol.SFTP,
        root: String = "/srv/romm/library"
    ) = UploadPlanner(
        tree = tree,
        platformResolver = PlatformResolver(catalog, emptyMap()),
        folderResolver = RemotePlatformFolderResolver(emptyMap(), emptyMap()),
        catalog = catalog,
        layout = RemoteLayout(root, template, protocol)
    )

    @Test
    fun `a gba file in a gba folder lands in roms gba`() {
        val tree = FakeLocalTree().file("/storage/emulated/0/ROMs/gba/Pokemon Emerald.gba")
        val p = planner(tree)
        val plan = p.plan(listOf(LocalSelection("/storage/emulated/0/ROMs/gba/Pokemon Emerald.gba", false)))
        val game = plan.games.single()
        assertEquals("gba", game.platform.argosySlug)
        assertEquals(PlatformCertainty.SOURCE_FOLDER, game.platform.certainty)
        assertEquals("/srv/romm/library/roms/gba", game.remoteGameDirectory)
        assertEquals(
            "/srv/romm/library/roms/gba/Pokemon Emerald.gba",
            p.remotePathFor(game, game.files.single())
        )
    }

    @Test
    fun `a multi disc folder keeps its structure under the platform folder`() {
        val tree = FakeLocalTree()
            .file("/sd/ROMs/psx/Final Fantasy VII/Disc 1.chd")
            .file("/sd/ROMs/psx/Final Fantasy VII/Disc 2.chd")
            .file("/sd/ROMs/psx/Final Fantasy VII/Disc 3.chd")
            .file("/sd/ROMs/psx/Final Fantasy VII/Final Fantasy VII.m3u", "Disc 1.chd\nDisc 2.chd\nDisc 3.chd\n")
        val p = planner(tree)
        val game = p.plan(listOf(LocalSelection("/sd/ROMs/psx/Final Fantasy VII", true))).games.single()
        assertEquals(GameLayout.FOLDER, game.layout)
        assertEquals("psx", game.platform.argosySlug)
        assertEquals("/srv/romm/library/roms/psx/Final Fantasy VII", game.remoteGameDirectory)
        assertEquals(4, game.files.size)
        assertEquals(
            "/srv/romm/library/roms/psx/Final Fantasy VII/Disc 2.chd",
            p.remotePathFor(game, game.files.first { it.relativePath == "Disc 2.chd" })
        )
    }

    @Test
    fun `a platform folder selection yields one game per file group and subfolder`() {
        val tree = FakeLocalTree()
            .file("/sd/ROMs/psx/Crash.cue", "FILE \"Crash.bin\" BINARY\n")
            .file("/sd/ROMs/psx/Crash.bin")
            .file("/sd/ROMs/psx/Spyro.chd")
            .file("/sd/ROMs/psx/FF7/Disc 1.chd")
        val plan = planner(tree).plan(listOf(LocalSelection("/sd/ROMs/psx", true)))
        assertEquals(setOf("Crash", "Spyro", "FF7"), plan.games.map { it.title }.toSet())
        assertEquals(GameLayout.FILE_SET, plan.games.first { it.title == "Crash" }.layout)
    }

    @Test
    fun `a library folder selection walks each platform folder`() {
        val tree = FakeLocalTree()
            .file("/sd/ROMs/gba/A.gba")
            .file("/sd/ROMs/snes/B.sfc")
            .file("/sd/ROMs/gc/C.rvz")
        val plan = planner(tree).plan(listOf(LocalSelection("/sd/ROMs", true)))
        val byTitle = plan.games.associateBy { it.title }
        assertEquals("/srv/romm/library/roms/gba", byTitle["A"]?.remoteGameDirectory)
        assertEquals("/srv/romm/library/roms/snes", byTitle["B"]?.remoteGameDirectory)
        assertEquals("/srv/romm/library/roms/ngc", byTitle["C"]?.remoteGameDirectory)
    }

    @Test
    fun `a platform first template places files under the platform`() {
        val tree = FakeLocalTree().file("/sd/ROMs/gba/A.gba")
        val p = planner(tree, template = "{platform}/roms", protocol = TransferProtocol.SMB, root = "")
        val game = p.plan(listOf(LocalSelection("/sd/ROMs/gba/A.gba", false))).games.single()
        assertEquals("gba/roms/A.gba", p.remotePathFor(game, game.files.single()))
    }

    @Test
    fun `an unknown platform has no destination and needs confirmation`() {
        val tree = FakeLocalTree().file("/sd/Download/game.iso")
        val plan = planner(tree).plan(listOf(LocalSelection("/sd/Download/game.iso", false)))
        val game = plan.games.single()
        assertNull(game.remoteGameDirectory)
        assertFalse(game.isPlaceable)
        assertTrue(plan.needsConfirmation)
    }

    @Test
    fun `manual platform choice places a game`() {
        val tree = FakeLocalTree().file("/sd/Download/game.iso")
        val p = planner(tree)
        val game = p.plan(listOf(LocalSelection("/sd/Download/game.iso", false))).games.single()
        val fixed = p.replatform(game, com.nendo.argosy.domain.model.upload.PlatformMatch("gc", PlatformCertainty.MANUAL))
        assertEquals("/srv/romm/library/roms/ngc", fixed.remoteGameDirectory)
        assertFalse(fixed.platform.needsConfirmation)
    }

    @Test
    fun `selecting the same game twice plans it once`() {
        val tree = FakeLocalTree().file("/sd/ROMs/psx/G.cue", "FILE \"G.bin\" BINARY\n").file("/sd/ROMs/psx/G.bin")
        val plan = planner(tree).plan(
            listOf(LocalSelection("/sd/ROMs/psx/G.cue", false), LocalSelection("/sd/ROMs/psx/G.bin", false))
        )
        assertEquals(1, plan.games.size)
    }

    @Test
    fun `unreadable folders are reported instead of dropped silently`() {
        val tree = FakeLocalTree().unreadable("/sd/Android/data/x")
        val plan = planner(tree).plan(listOf(LocalSelection("/sd/Android/data/x", true)))
        assertEquals(listOf("/sd/Android/data/x"), plan.unreadable)
    }
}
