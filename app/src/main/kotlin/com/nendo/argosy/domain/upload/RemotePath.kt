package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.TransferProtocol

class InvalidRemotePathException(
    val reason: Reason,
    val offendingValue: String
) : IllegalArgumentException("Invalid remote path ($reason)") {

    enum class Reason {
        EMPTY,
        TRAVERSAL,
        CONTROL_CHARACTER,
        RESERVED_CHARACTER,
        RESERVED_NAME,
        TRAILING_DOT_OR_SPACE,
        ABSOLUTE,
        TOO_LONG,
        OUTSIDE_ROOT
    }
}

/**
 * Remote path rules shared by every transfer protocol. Paths always use `/` as the separator;
 * protocol clients translate to their own wire form. Every helper either returns a normalised
 * path or throws [InvalidRemotePathException], so a path that reaches a client has already been
 * proven to stay inside the configured library root.
 */
object RemotePath {
    const val SEPARATOR = '/'

    private const val MAX_SEGMENT_BYTES = 255
    private val SMB_RESERVED_CHARACTERS = setOf('\\', ':', '*', '?', '"', '<', '>', '|')
    private val SMB_RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

    fun validateSegment(segment: String, protocol: TransferProtocol) {
        if (segment.isEmpty()) throw InvalidRemotePathException(InvalidRemotePathException.Reason.EMPTY, segment)
        if (segment == "." || segment == "..") {
            throw InvalidRemotePathException(InvalidRemotePathException.Reason.TRAVERSAL, segment)
        }
        if (segment.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_BYTES) {
            throw InvalidRemotePathException(InvalidRemotePathException.Reason.TOO_LONG, segment)
        }
        if (segment.any { it.code < 0x20 || it.code == 0x7F }) {
            throw InvalidRemotePathException(InvalidRemotePathException.Reason.CONTROL_CHARACTER, segment)
        }
        if (segment.contains(SEPARATOR)) {
            throw InvalidRemotePathException(InvalidRemotePathException.Reason.RESERVED_CHARACTER, segment)
        }
        if (protocol == TransferProtocol.SMB) {
            if (segment.any { it in SMB_RESERVED_CHARACTERS }) {
                throw InvalidRemotePathException(InvalidRemotePathException.Reason.RESERVED_CHARACTER, segment)
            }
            if (segment.endsWith('.') || segment.endsWith(' ')) {
                throw InvalidRemotePathException(InvalidRemotePathException.Reason.TRAILING_DOT_OR_SPACE, segment)
            }
            if (segment.substringBefore('.').uppercase() in SMB_RESERVED_NAMES) {
                throw InvalidRemotePathException(InvalidRemotePathException.Reason.RESERVED_NAME, segment)
            }
        }
    }

    /**
     * The library root as typed by the user, normalised. SFTP roots may be absolute (`/srv/romm`);
     * SMB roots are always relative to the share, and an empty string is the share root.
     */
    fun normalizeRoot(root: String, protocol: TransferProtocol): String {
        val unified = root.trim().replace('\\', SEPARATOR)
        val absolute = unified.startsWith(SEPARATOR)
        if (absolute && protocol == TransferProtocol.SMB) {
            val segments = splitSegments(unified, protocol)
            return segments.joinToString(SEPARATOR.toString())
        }
        val segments = splitSegments(unified, protocol)
        val joined = segments.joinToString(SEPARATOR.toString())
        return if (absolute) "$SEPARATOR$joined" else joined
    }

    fun relativeSegments(relative: String, protocol: TransferProtocol): List<String> {
        if (relative.startsWith(SEPARATOR) || relative.startsWith('\\')) {
            throw InvalidRemotePathException(InvalidRemotePathException.Reason.ABSOLUTE, relative)
        }
        val segments = relative.split(SEPARATOR).filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw InvalidRemotePathException(InvalidRemotePathException.Reason.EMPTY, relative)
        segments.forEach { validateSegment(it, protocol) }
        return segments
    }

    fun resolve(root: String, relative: String, protocol: TransferProtocol): String {
        val segments = relativeSegments(relative, protocol)
        return join(root, segments)
    }

    fun child(parent: String, name: String, protocol: TransferProtocol): String {
        validateSegment(name, protocol)
        return join(parent, listOf(name))
    }

    fun isWithin(root: String, candidate: String): Boolean {
        if (candidate.split(SEPARATOR).any { it == ".." || it == "." }) return false
        return when {
            root.isEmpty() -> !candidate.startsWith(SEPARATOR)
            root == SEPARATOR.toString() -> candidate.startsWith(SEPARATOR)
            else -> candidate == root || candidate.startsWith("$root$SEPARATOR")
        }
    }

    fun requireWithin(root: String, candidate: String) {
        if (!isWithin(root, candidate)) {
            throw InvalidRemotePathException(InvalidRemotePathException.Reason.OUTSIDE_ROOT, candidate)
        }
    }

    fun parent(path: String): String? {
        val idx = path.lastIndexOf(SEPARATOR)
        return when {
            idx < 0 -> if (path.isEmpty()) null else ""
            idx == 0 -> if (path.length > 1) SEPARATOR.toString() else null
            else -> path.substring(0, idx)
        }
    }

    fun name(path: String): String = path.substringAfterLast(SEPARATOR)

    private fun join(root: String, segments: List<String>): String {
        val tail = segments.joinToString(SEPARATOR.toString())
        return when {
            root.isEmpty() -> tail
            root.endsWith(SEPARATOR) -> "$root$tail"
            else -> "$root$SEPARATOR$tail"
        }
    }

    private fun splitSegments(path: String, protocol: TransferProtocol): List<String> {
        val segments = path.split(SEPARATOR).filter { it.isNotEmpty() && it != "." }
        segments.forEach { validateSegment(it, protocol) }
        return segments
    }
}
