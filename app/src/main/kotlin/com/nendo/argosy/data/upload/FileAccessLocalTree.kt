package com.nendo.argosy.data.upload

import com.nendo.argosy.data.storage.FileAccessLayer
import com.nendo.argosy.domain.upload.LocalEntry
import com.nendo.argosy.domain.upload.LocalSource
import com.nendo.argosy.domain.upload.LocalTree
import java.io.File
import java.io.InputStream

/**
 * Read-only view of local storage for upload planning, through [FileAccessLayer]. A directory entry
 * whose canonical location leaves its parent is dropped, so a symlink cannot pull files from outside
 * the folder the user picked. Nothing here writes or deletes.
 */
class FileAccessLocalTree(private val files: FileAccessLayer) : LocalTree, LocalSource {

    override fun list(directory: String): List<LocalEntry>? {
        val listing = files.listFiles(directory) ?: return null
        val parentCanonical = canonical(directory)
        return listing
            .filter { info -> !info.isDirectory || parentCanonical == null || staysWithin(parentCanonical, info.path) }
            .map { info -> LocalEntry(info.name, info.path, info.isDirectory, if (info.isDirectory) 0 else info.size) }
    }

    override fun readText(path: String, maxBytes: Int): String? {
        if (files.length(path) > maxBytes) return null
        return files.getInputStream(path)?.use { stream ->
            val buffer = ByteArray(maxBytes)
            var filled = 0
            while (filled < maxBytes) {
                val read = stream.read(buffer, filled, maxBytes - filled)
                if (read < 0) break
                filled += read
            }
            String(buffer, 0, filled, Charsets.UTF_8)
        }
    }

    override fun open(path: String): InputStream? = files.getInputStream(path)

    private fun staysWithin(parentCanonical: String, child: String): Boolean {
        val childCanonical = canonical(child) ?: return true
        return childCanonical.startsWith("$parentCanonical/")
    }

    private fun canonical(path: String): String? =
        runCatching { files.getTransformedFile(path).canonicalPath.trimEnd('/') }.getOrNull()
}
