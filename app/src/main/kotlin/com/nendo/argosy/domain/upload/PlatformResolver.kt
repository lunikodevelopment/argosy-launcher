package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.PlatformCertainty
import com.nendo.argosy.domain.model.upload.PlatformMatch

interface PlatformCatalog {
    fun canonicalSlug(raw: String): String?

    fun platformsForUnambiguousExtension(extension: String): List<String>

    fun displayName(slug: String): String?
}

/**
 * Destination platform for a game, in precedence order: a user folder mapping, the platform Argosy
 * already recorded for the game, a recognised source folder name, an extension that belongs to
 * exactly one platform, then nothing. A shared extension never decides on its own.
 */
class PlatformResolver(
    private val catalog: PlatformCatalog,
    private val localFolderMappings: Map<String, String>
) {
    fun resolve(
        sourceDirectory: String,
        fileNames: List<String>,
        knownGameSlug: String? = null
    ): PlatformMatch {
        mappedPlatformFor(sourceDirectory)?.let { return PlatformMatch(it, PlatformCertainty.USER_MAPPING) }

        knownGameSlug?.let(catalog::canonicalSlug)?.let {
            return PlatformMatch(it, PlatformCertainty.GAME_METADATA)
        }

        folderPlatformFor(sourceDirectory)?.let { return PlatformMatch(it, PlatformCertainty.SOURCE_FOLDER) }

        val candidates = fileNames
            .map { it.substringAfterLast('.', "").lowercase() }
            .filter { it.isNotEmpty() }
            .map { catalog.platformsForUnambiguousExtension(it).toSet() }
            .filter { it.isNotEmpty() }
        if (candidates.isNotEmpty()) {
            val common = candidates.reduce { acc, set -> acc intersect set }
            if (common.size == 1) return PlatformMatch(common.first(), PlatformCertainty.EXTENSION)
        }
        return PlatformMatch.UNKNOWN
    }

    fun isPlatformFolder(directory: String): Boolean {
        val normalized = normalizeLocal(directory)
        if (localFolderMappings.keys.any { normalizeLocal(it) == normalized }) return true
        val name = normalized.substringAfterLast('/')
        return name.isNotEmpty() && catalog.canonicalSlug(name) != null
    }

    private fun mappedPlatformFor(directory: String): String? {
        val normalized = normalizeLocal(directory)
        return localFolderMappings.entries
            .map { normalizeLocal(it.key) to it.value }
            .filter { (prefix, _) -> normalized == prefix || normalized.startsWith("$prefix/") }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.second
            ?.let { catalog.canonicalSlug(it) ?: it.lowercase() }
    }

    private fun folderPlatformFor(directory: String): String? {
        val segments = normalizeLocal(directory).split('/').filter { it.isNotEmpty() }
        return segments.asReversed().asSequence().take(MAX_FOLDER_DEPTH).mapNotNull { segment ->
            catalog.canonicalSlug(segment)
        }.firstOrNull()
    }

    private fun normalizeLocal(path: String): String = path.trimEnd('/').lowercase()

    companion object {
        private const val MAX_FOLDER_DEPTH = 3
    }
}
