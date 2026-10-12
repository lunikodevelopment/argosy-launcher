package com.nendo.argosy.domain.upload

data class LocalEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val baseName: String get() = if (name.contains('.')) name.substringBeforeLast('.') else name
}

interface LocalTree {
    fun list(directory: String): List<LocalEntry>?

    fun readText(path: String, maxBytes: Int): String?
}

data class FileGroup(
    val primary: LocalEntry,
    val members: List<LocalEntry>,
    val missingReferences: List<String>
) {
    val isSet: Boolean get() = members.size > 1 || primary.extension in GameFileGrouper.DESCRIPTOR_EXTENSIONS
}

/**
 * Partition of one directory's loose files into games. A playlist, cue sheet, GDI table, CloneCD
 * or Alcohol descriptor owns every sibling it names; playlists claim first, including the tracks
 * of cue sheets they list. Unclaimed files are single-file games. Descriptors are read only.
 */
class GameFileGrouper(private val tree: LocalTree) {

    fun group(files: List<LocalEntry>): List<FileGroup> {
        val visible = files.filter { !it.isDirectory && !it.name.startsWith(".") }
        val byName = visible.associateBy { it.name.lowercase() }
        val claimed = mutableSetOf<String>()
        val groups = mutableListOf<FileGroup>()

        fun claim(primary: LocalEntry, referenced: List<String>) {
            val members = mutableListOf(primary)
            val missing = mutableListOf<String>()
            referenced.forEach { ref ->
                val match = byName[ref.lowercase()]
                when {
                    match == null -> missing += ref
                    match.name.lowercase() !in claimed && match != primary -> members += match
                }
            }
            members.forEach { claimed += it.name.lowercase() }
            groups += FileGroup(primary, members.distinctBy { it.path }, missing)
        }

        visible.filter { it.extension == "m3u" }.sortedBy { it.name.lowercase() }.forEach { playlist ->
            if (playlist.name.lowercase() in claimed) return@forEach
            val discs = referencesOf(playlist)
            val nested = discs.flatMap { disc ->
                val entry = byName[disc.lowercase()]
                if (entry != null && entry.extension in SHEET_EXTENSIONS) referencesOf(entry) else emptyList()
            }
            claim(playlist, discs + nested)
        }

        visible.filter { it.extension in SHEET_EXTENSIONS || it.extension in PAIRED_DESCRIPTORS.keys }
            .sortedBy { it.name.lowercase() }
            .forEach { descriptor ->
                if (descriptor.name.lowercase() in claimed) return@forEach
                claim(descriptor, referencesOf(descriptor))
            }

        visible.filter { it.name.lowercase() !in claimed }.sortedBy { it.name.lowercase() }.forEach { file ->
            claimed += file.name.lowercase()
            groups += FileGroup(file, listOf(file), emptyList())
        }
        return groups
    }

    fun groupContaining(target: LocalEntry, siblings: List<LocalEntry>): FileGroup =
        group(siblings).firstOrNull { group -> group.members.any { it.path == target.path } }
            ?: FileGroup(target, listOf(target), emptyList())

    private fun referencesOf(descriptor: LocalEntry): List<String> {
        PAIRED_DESCRIPTORS[descriptor.extension]?.let { companions ->
            return companions.map { "${descriptor.baseName}.$it" }
        }
        val text = tree.readText(descriptor.path, MAX_DESCRIPTOR_BYTES) ?: return emptyList()
        return when (descriptor.extension) {
            "cue" -> parseCue(text)
            "gdi" -> parseGdi(text)
            "m3u" -> parseM3u(text)
            else -> emptyList()
        }.map { it.replace('\\', '/').substringAfterLast('/') }.distinct()
    }

    companion object {
        val SHEET_EXTENSIONS = setOf("cue", "gdi")
        val PAIRED_DESCRIPTORS = mapOf(
            "ccd" to listOf("img", "sub"),
            "mds" to listOf("mdf")
        )
        val DESCRIPTOR_EXTENSIONS = setOf("m3u", "cue", "gdi", "ccd", "mds")
        private const val MAX_DESCRIPTOR_BYTES = 256 * 1024
        private val CUE_FILE = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))""", RegexOption.IGNORE_CASE)
        private val GDI_TRACK = Regex("""^\s*\d+\s+\d+\s+\d+\s+\d+\s+(?:"([^"]+)"|(\S+))""")

        fun parseCue(text: String): List<String> = text.lineSequence().mapNotNull { line ->
            CUE_FILE.find(line)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        }.toList()

        fun parseGdi(text: String): List<String> = text.lineSequence().drop(1).mapNotNull { line ->
            GDI_TRACK.find(line)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        }.toList()

        fun parseM3u(text: String): List<String> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }
}
