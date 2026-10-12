package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.RemoteFolderSource
import com.nendo.argosy.domain.model.upload.RemotePlatformFolder

/**
 * RomM's default `fs_slug` for an Argosy canonical platform slug, where the two differ.
 *
 * Values are copied from RomM's `backend/utils/platform_slugs.py` (romm 5.4.0) and are matched to
 * Argosy slugs through the RomM spelling Argosy already lists as an alias in PlatformDefinitions.
 * Slugs absent from this map and present in [ROMM_IDENTICAL_SLUGS] use the Argosy slug unchanged.
 * Anything else has no default and needs a user mapping or a synced RomM platform.
 */
object RommDefaultFolders {
    val DIFFERING_SLUGS: Map<String, String> = mapOf(
        "n64dd" to "64dd",
        "gc" to "ngc",
        "gameandwatch" to "g-and-w",
        "vb" to "virtualboy",
        "pokemini" to "pokemon-mini",
        "dsi" to "nintendo-dsi",
        "vita" to "psvita",
        "scd" to "segacd",
        "32x" to "sega32",
        "dreamcast" to "dc",
        "gg" to "gamegear",
        "jaguarcd" to "atari-jaguar-cd",
        "atarist" to "atari-st",
        "tgcd" to "turbografx-cd",
        "pcfx" to "pc-fx",
        "neogeocd" to "neo-geo-cd",
        "ngp" to "neo-geo-pocket",
        "ngpc" to "neo-geo-pocket-color",
        "vic20" to "vic-20",
        "plus4" to "c-plus-4",
        "amigacd32" to "amiga-cd32",
        "amstradcpc" to "acpc",
        "fmtowns" to "fm-towns",
        "pc8800" to "pc-8800-series",
        "pc9800" to "pc-9800-series",
        "x68000" to "sharp-x68000",
        "zx" to "zxs",
        "ti83" to "ti-83",
        "cdi" to "philips-cd-i",
        "coleco" to "colecovision",
        "wsc" to "wonderswan-color",
        "n3ds" to "new-nintendo-3ds"
    )

    val ROMM_IDENTICAL_SLUGS: Set<String> = setOf(
        "nes", "fds", "snes", "satellaview", "n64", "wii", "wiiu", "switch", "gb", "gbc", "gba",
        "nds", "3ds", "psx", "ps2", "ps3", "ps4", "ps5", "psp", "sg1000", "sms", "genesis", "pico",
        "saturn", "xbox", "xbox360", "xboxone", "atari2600", "atari5200", "atari7800", "jaguar",
        "lynx", "atari8bit", "tg16", "supergrafx", "c64", "c128", "cpet", "amiga", "arcade", "cps1",
        "cps2", "cps3", "model2", "model3", "appleii", "enterprise", "bbcmicro", "dos", "msx", "msx2",
        "mac", "scummvm", "zx81", "j2me", "3do", "intellivision", "vectrex", "wonderswan", "playdia",
        "evercade", "gamate", "gp32", "supervision", "playdate", "nuon", "arduboy", "uzebox"
    )

    fun defaultFolderFor(argosySlug: String): String? {
        val lower = argosySlug.lowercase()
        DIFFERING_SLUGS[lower]?.let { return it }
        return lower.takeIf { it in ROMM_IDENTICAL_SLUGS }
    }
}

class RemotePlatformFolderResolver(
    private val userMappings: Map<String, String>,
    private val rommLibraryFolders: Map<String, String>
) {
    fun resolve(argosySlug: String): RemotePlatformFolder? {
        val lower = argosySlug.lowercase()
        userMappings[lower]?.takeIf { it.isNotBlank() }?.let {
            return RemotePlatformFolder(it, RemoteFolderSource.USER_MAPPING)
        }
        rommLibraryFolders[lower]?.takeIf { it.isNotBlank() }?.let {
            return RemotePlatformFolder(it, RemoteFolderSource.ROMM_LIBRARY)
        }
        RommDefaultFolders.defaultFolderFor(lower)?.let {
            return RemotePlatformFolder(it, RemoteFolderSource.ROMM_DEFAULT)
        }
        return null
    }
}
