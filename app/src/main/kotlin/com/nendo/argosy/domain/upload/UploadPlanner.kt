package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.GameLayout
import com.nendo.argosy.domain.model.upload.LocalSourceFile
import com.nendo.argosy.domain.model.upload.PlannedGame
import com.nendo.argosy.domain.model.upload.PlatformMatch
import com.nendo.argosy.domain.model.upload.RemotePlatformFolder
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadProfile
import java.security.MessageDigest

data class LocalSelection(
    val path: String,
    val isDirectory: Boolean,
    val knownPlatformSlug: String? = null,
    val titleOverride: String? = null
)

data class RemoteLayout(
    val libraryRoot: String,
    val platformTemplate: String = DEFAULT_PLATFORM_TEMPLATE,
    val protocol: TransferProtocol
) {
    init {
        require(platformTemplate.split(PLATFORM_TOKEN).size == 2) { "template needs exactly one $PLATFORM_TOKEN" }
    }

    fun platformDirectory(folder: String): String {
        val relative = platformTemplate.replace(PLATFORM_TOKEN, folder)
        return RemotePath.resolve(libraryRoot, relative, protocol)
    }

    companion object {
        const val PLATFORM_TOKEN = "{platform}"
        const val DEFAULT_PLATFORM_TEMPLATE = UploadProfile.DEFAULT_PLATFORM_TEMPLATE
    }
}

data class UploadPlan(
    val games: List<PlannedGame>,
    val unreadable: List<String>,
    val missingReferences: Map<String, List<String>>
) {
    val needsConfirmation: Boolean
        get() = games.any { it.platform.needsConfirmation || !it.isPlaceable }
}

/**
 * Turns the user's file and folder selections into games with remote destinations. A folder named
 * after a platform contributes one game per loose file group and one per subfolder; a folder that
 * holds platform folders is a library and contributes each of them; any other folder is one game
 * uploaded with its internal structure intact. A selected file brings along every sibling its
 * descriptor group names.
 */
class UploadPlanner(
    private val tree: LocalTree,
    private val platformResolver: PlatformResolver,
    private val folderResolver: RemotePlatformFolderResolver,
    private val catalog: PlatformCatalog,
    private val layout: RemoteLayout
) {
    private val grouper = GameFileGrouper(tree)

    fun plan(selections: List<LocalSelection>): UploadPlan {
        val games = mutableListOf<PlannedGame>()
        val unreadable = mutableListOf<String>()
        val missing = mutableMapOf<String, List<String>>()

        selections.forEach { selection ->
            if (selection.isDirectory) {
                planDirectory(selection, games, unreadable, missing)
            } else {
                planFile(selection, games, unreadable, missing)
            }
        }
        val unique = games.distinctBy { it.id }
        return UploadPlan(unique, unreadable, missing)
    }

    fun replatform(game: PlannedGame, match: PlatformMatch): PlannedGame {
        val folder = match.argosySlug?.let(folderResolver::resolve)
        return game.copy(
            platform = match,
            remotePlatformFolder = folder,
            remoteGameDirectory = folder?.let { remoteGameDirectory(it, game.layout, game.sourceDirectory) }
        )
    }

    fun withRemoteFolder(game: PlannedGame, folder: RemotePlatformFolder): PlannedGame = game.copy(
        remotePlatformFolder = folder,
        remoteGameDirectory = remoteGameDirectory(folder, game.layout, game.sourceDirectory)
    )

    fun remotePathFor(game: PlannedGame, file: LocalSourceFile): String? {
        val directory = game.remoteGameDirectory ?: return null
        return RemotePath.resolve(directory, file.relativePath, layout.protocol)
    }

    private fun planFile(
        selection: LocalSelection,
        games: MutableList<PlannedGame>,
        unreadable: MutableList<String>,
        missing: MutableMap<String, List<String>>
    ) {
        val parent = parentOf(selection.path)
        val siblings = tree.list(parent)
        val target = siblings?.firstOrNull { it.path == selection.path }
        if (siblings == null || target == null) {
            unreadable += selection.path
            return
        }
        val group = grouper.groupContaining(target, siblings)
        if (group.missingReferences.isNotEmpty()) missing[group.primary.path] = group.missingReferences
        games += gameFromGroup(group, parent, selection.knownPlatformSlug, selection.titleOverride)
    }

    private fun planDirectory(
        selection: LocalSelection,
        games: MutableList<PlannedGame>,
        unreadable: MutableList<String>,
        missing: MutableMap<String, List<String>>
    ) {
        val children = tree.list(selection.path)
        if (children == null) {
            unreadable += selection.path
            return
        }
        when {
            isPlatformDirectory(selection.path) ->
                planPlatformDirectory(selection.path, children, selection.knownPlatformSlug, games, unreadable, missing)
            children.any { it.isDirectory && isPlatformDirectory(it.path) } ->
                children.filter { it.isDirectory && !it.name.startsWith(".") }.forEach { child ->
                    if (isPlatformDirectory(child.path)) {
                        val grandChildren = tree.list(child.path)
                        if (grandChildren == null) {
                            unreadable += child.path
                        } else {
                            planPlatformDirectory(child.path, grandChildren, null, games, unreadable, missing)
                        }
                    } else {
                        folderGame(child.path, selection.knownPlatformSlug, null)?.let { games += it }
                            ?: run { unreadable += child.path }
                    }
                }
            else -> folderGame(selection.path, selection.knownPlatformSlug, selection.titleOverride)
                ?.let { games += it } ?: run { unreadable += selection.path }
        }
    }

    private fun planPlatformDirectory(
        directory: String,
        children: List<LocalEntry>,
        knownSlug: String?,
        games: MutableList<PlannedGame>,
        unreadable: MutableList<String>,
        missing: MutableMap<String, List<String>>
    ) {
        grouper.group(children.filter { !it.isDirectory }).forEach { group ->
            if (group.missingReferences.isNotEmpty()) missing[group.primary.path] = group.missingReferences
            games += gameFromGroup(group, directory, knownSlug, null)
        }
        children.filter { it.isDirectory && !it.name.startsWith(".") }.forEach { child ->
            folderGame(child.path, knownSlug, null)?.let { games += it } ?: run { unreadable += child.path }
        }
    }

    private fun gameFromGroup(group: FileGroup, directory: String, knownSlug: String?, title: String?): PlannedGame {
        val files = group.members.map { LocalSourceFile(it.path, it.name, it.size) }
        val layoutKind = if (group.isSet) GameLayout.FILE_SET else GameLayout.SINGLE_FILE
        val match = platformResolver.resolve(directory, group.members.map { it.name }, knownSlug)
        return buildGame(
            title = title ?: group.primary.baseName,
            layoutKind = layoutKind,
            match = match,
            sourceDirectory = directory,
            files = files,
            identity = group.primary.path
        )
    }

    private fun folderGame(directory: String, knownSlug: String?, title: String?): PlannedGame? {
        val files = mutableListOf<LocalSourceFile>()
        if (!walk(directory, "", files)) return null
        if (files.isEmpty()) return null
        val parent = parentOf(directory)
        val match = platformResolver.resolve(parent, files.map { RemotePath.name(it.relativePath) }, knownSlug)
            .takeIf { it.argosySlug != null }
            ?: platformResolver.resolve(directory, files.map { RemotePath.name(it.relativePath) }, knownSlug)
        return buildGame(
            title = title ?: nameOf(directory),
            layoutKind = GameLayout.FOLDER,
            match = match,
            sourceDirectory = directory,
            files = files,
            identity = directory
        )
    }

    private fun walk(directory: String, prefix: String, out: MutableList<LocalSourceFile>, depth: Int = 0): Boolean {
        if (depth > MAX_FOLDER_DEPTH) return false
        val children = tree.list(directory) ?: return false
        children.filter { !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }.forEach { child ->
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                if (!walk(child.path, relative, out, depth + 1)) return false
            } else {
                out += LocalSourceFile(child.path, relative, child.size)
            }
        }
        return true
    }

    private fun buildGame(
        title: String,
        layoutKind: GameLayout,
        match: PlatformMatch,
        sourceDirectory: String,
        files: List<LocalSourceFile>,
        identity: String
    ): PlannedGame {
        val folder = match.argosySlug?.let(folderResolver::resolve)
        return PlannedGame(
            id = stableId(identity),
            title = title,
            layout = layoutKind,
            platform = match,
            sourceDirectory = sourceDirectory,
            files = files,
            remotePlatformFolder = folder,
            remoteGameDirectory = folder?.let { remoteGameDirectory(it, layoutKind, sourceDirectory) }
        )
    }

    private fun remoteGameDirectory(folder: RemotePlatformFolder, kind: GameLayout, sourceDirectory: String): String {
        val platformDirectory = layout.platformDirectory(folder.folderName)
        return when (kind) {
            GameLayout.FOLDER -> RemotePath.child(platformDirectory, nameOf(sourceDirectory), layout.protocol)
            GameLayout.SINGLE_FILE, GameLayout.FILE_SET -> platformDirectory
        }
    }

    private fun isPlatformDirectory(path: String): Boolean = platformResolver.isPlatformFolder(path)

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

    private fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun stableId(identity: String): String =
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .take(ID_BYTES).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_FOLDER_DEPTH = 8
        private const val ID_BYTES = 12
    }
}
