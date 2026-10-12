package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.RemoteFolderSource
import com.nendo.argosy.domain.model.upload.RemotePlatformFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePlatformFolderResolverTest {

    @Test
    fun `argosy gamecube maps to the romm ngc folder by default`() {
        val resolver = RemotePlatformFolderResolver(emptyMap(), emptyMap())
        assertEquals(RemotePlatformFolder("ngc", RemoteFolderSource.ROMM_DEFAULT), resolver.resolve("gc"))
        assertEquals(RemotePlatformFolder("dc", RemoteFolderSource.ROMM_DEFAULT), resolver.resolve("dreamcast"))
        assertEquals(RemotePlatformFolder("gba", RemoteFolderSource.ROMM_DEFAULT), resolver.resolve("gba"))
    }

    @Test
    fun `the synced romm library folder beats the default and the user mapping beats both`() {
        val resolver = RemotePlatformFolderResolver(
            userMappings = mapOf("psx" to "PlayStation"),
            rommLibraryFolders = mapOf("psx" to "ps1", "gc" to "gamecube")
        )
        assertEquals(RemotePlatformFolder("PlayStation", RemoteFolderSource.USER_MAPPING), resolver.resolve("psx"))
        assertEquals(RemotePlatformFolder("gamecube", RemoteFolderSource.ROMM_LIBRARY), resolver.resolve("gc"))
    }

    @Test
    fun `platforms without a verified romm default need a mapping`() {
        val resolver = RemotePlatformFolderResolver(emptyMap(), emptyMap())
        assertNull(resolver.resolve("mame"))
        assertNull(resolver.resolve("naomi"))
    }

    @Test
    fun `every required console category has a default folder`() {
        val resolver = RemotePlatformFolderResolver(emptyMap(), emptyMap())
        val expected = mapOf(
            "nes" to "nes", "snes" to "snes", "gb" to "gb", "gbc" to "gbc", "gba" to "gba",
            "n64" to "n64", "nds" to "nds", "3ds" to "3ds", "gc" to "ngc", "wii" to "wii",
            "psx" to "psx", "ps2" to "ps2", "psp" to "psp", "dreamcast" to "dc",
            "genesis" to "genesis", "saturn" to "saturn", "arcade" to "arcade"
        )
        expected.forEach { (slug, folder) -> assertEquals(slug, folder, resolver.resolve(slug)?.folderName) }
    }
}
