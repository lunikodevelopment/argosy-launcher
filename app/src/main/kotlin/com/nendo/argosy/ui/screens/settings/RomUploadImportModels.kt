package com.nendo.argosy.ui.screens.settings

import com.nendo.argosy.domain.model.upload.DuplicatePolicy

enum class RomUploadImportRow { SERVER, POLICY, UNCERTAIN, UPLOAD, CANCEL }

enum class RomUploadImportKind { PICKED, MISSING }

enum class RomUploadImportPhase { PLANNING, READY, EMPTY }

data class RomUploadImportState(
    val kind: RomUploadImportKind = RomUploadImportKind.PICKED,
    val browserOpen: Boolean = false,
    val visible: Boolean = false,
    val phase: RomUploadImportPhase = RomUploadImportPhase.PLANNING,
    val serverNames: List<String> = emptyList(),
    val serverIndex: Int = 0,
    val policy: DuplicatePolicy = DuplicatePolicy.DEFAULT,
    val includeUncertain: Boolean = false,
    val confidentGames: Int = 0,
    val uncertainGames: Int = 0,
    val unplaceableGames: Int = 0,
    val unreadable: Int = 0,
    val totalBytes: Long = 0L,
    val focusIndex: Int = 0,
    val overwriteConfirmFocus: Int? = null
) {
    val serverName: String get() = serverNames.getOrNull(serverIndex).orEmpty()
    val focusedRow: RomUploadImportRow get() = RomUploadImportRow.entries[focusIndex]
    val uploadCount: Int get() = confidentGames + if (includeUncertain) uncertainGames else 0
    val canUpload: Boolean get() = phase == RomUploadImportPhase.READY && uploadCount > 0
    val isOpen: Boolean get() = visible || browserOpen
}
