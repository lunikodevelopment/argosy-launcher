package com.nendo.argosy.data.upload

import com.nendo.argosy.data.platform.PlatformDefinitions
import com.nendo.argosy.domain.upload.PlatformCatalog

/**
 * [PlatformCatalog] over Argosy's platform registry. A raw name resolves when it is a registered
 * slug or alias, or when it equals a platform's full or short display name.
 */
object PlatformDefinitionsCatalog : PlatformCatalog {

    override fun canonicalSlug(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        PlatformDefinitions.getBySlug(trimmed)?.let { return it.slug }
        return PlatformDefinitions.getAll().firstOrNull {
            it.name.equals(trimmed, ignoreCase = true) || it.shortName.equals(trimmed, ignoreCase = true)
        }?.slug
    }

    override fun platformsForUnambiguousExtension(extension: String): List<String> =
        PlatformDefinitions.getPlatformsForExtension(extension)
            .filter { extension.lowercase() in it.primaryExtensions }
            .map { it.slug }

    override fun displayName(slug: String): String? = PlatformDefinitions.getBySlug(slug)?.name
}
