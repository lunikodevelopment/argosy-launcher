package com.nendo.argosy.domain.upload

class FakeLocalTree : LocalTree {
    private val files = linkedMapOf<String, ByteArray>()
    private val directories = mutableSetOf<String>()
    private val unreadable = mutableSetOf<String>()

    fun file(path: String, content: String = "x"): FakeLocalTree = file(path, content.toByteArray())

    fun file(path: String, content: ByteArray): FakeLocalTree {
        files[path] = content
        var parent = path.substringBeforeLast('/')
        while (parent.isNotEmpty()) {
            directories += parent
            parent = parent.substringBeforeLast('/', "")
        }
        return this
    }

    fun unreadable(path: String): FakeLocalTree {
        unreadable += path
        directories += path
        return this
    }

    fun bytes(path: String): ByteArray? = files[path]

    override fun list(directory: String): List<LocalEntry>? {
        if (directory in unreadable) return null
        if (directory !in directories) return null
        val prefix = "$directory/"
        val childFiles = files.filterKeys { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { (path, bytes) -> LocalEntry(path.substringAfterLast('/'), path, false, bytes.size.toLong()) }
        val childDirs = directories.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { LocalEntry(it.substringAfterLast('/'), it, true, 0L) }
        return childDirs + childFiles
    }

    override fun readText(path: String, maxBytes: Int): String? = files[path]?.take(maxBytes)?.toByteArray()?.decodeToString()
}
