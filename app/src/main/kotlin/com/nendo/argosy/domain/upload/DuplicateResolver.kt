package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.DuplicatePolicy

sealed interface DuplicateDecision {
    data object Proceed : DuplicateDecision
    data object Skip : DuplicateDecision
    data object Abort : DuplicateDecision
    data class Overwrite(val replacing: RemoteStat) : DuplicateDecision
    data class RenameTo(val path: String) : DuplicateDecision
}

object DuplicateResolver {
    const val MAX_RENAME_ATTEMPTS = 999

    fun decide(
        policy: DuplicatePolicy,
        overwriteConfirmed: Boolean,
        existing: RemoteStat?,
        localSize: Long,
        keepBothName: () -> String?
    ): DuplicateDecision {
        if (existing == null) return DuplicateDecision.Proceed
        if (existing.isDirectory) return DuplicateDecision.Abort
        return when (policy) {
            DuplicatePolicy.SKIP -> DuplicateDecision.Skip
            DuplicatePolicy.CANCEL -> DuplicateDecision.Abort
            DuplicatePolicy.OVERWRITE ->
                if (overwriteConfirmed) DuplicateDecision.Overwrite(existing) else DuplicateDecision.Abort
            DuplicatePolicy.KEEP_BOTH -> keepBothName()?.let(DuplicateDecision::RenameTo) ?: DuplicateDecision.Abort
        }
    }

    fun keepBothCandidates(path: String): Sequence<String> {
        val parent = RemotePath.parent(path)
        val name = RemotePath.name(path)
        val stem = if (name.contains('.') && !name.startsWith(".")) name.substringBeforeLast('.') else name
        val ext = if (stem.length != name.length) name.substring(stem.length) else ""
        return (2..MAX_RENAME_ATTEMPTS).asSequence().map { n ->
            val candidate = "$stem ($n)$ext"
            when {
                parent == null || parent.isEmpty() -> candidate
                parent == "/" -> "/$candidate"
                else -> "$parent/$candidate"
            }
        }
    }
}
