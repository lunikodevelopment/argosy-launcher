package com.nendo.argosy.domain.upload

class FakePlatformCatalog : PlatformCatalog {
    private val aliases = mapOf(
        "gba" to "gba", "gameboyadvance" to "gba", "psx" to "psx", "ps1" to "psx",
        "dreamcast" to "dreamcast", "dc" to "dreamcast", "gc" to "gc", "ngc" to "gc", "gamecube" to "gc",
        "nds" to "nds", "snes" to "snes"
    )
    private val unambiguous = mapOf(
        "gba" to listOf("gba"),
        "sfc" to listOf("snes"),
        "nds" to listOf("nds"),
        "gdi" to listOf("dreamcast"),
        "pbp" to listOf("psx", "psp")
    )

    override fun canonicalSlug(raw: String): String? = aliases[raw.lowercase()]

    override fun platformsForUnambiguousExtension(extension: String): List<String> =
        unambiguous[extension.lowercase()].orEmpty()

    override fun displayName(slug: String): String? = slug.uppercase()
}
