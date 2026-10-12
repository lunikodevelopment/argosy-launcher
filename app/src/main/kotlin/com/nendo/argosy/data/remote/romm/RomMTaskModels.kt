package com.nendo.argosy.data.remote.romm

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RomMScanRequest(
    @Json(name = "type") val type: String,
    @Json(name = "platform_fs_slugs") val platformFsSlugs: List<String>
)

@JsonClass(generateAdapter = true)
data class RomMScanStats(
    @Json(name = "scanned_roms") val scannedRoms: Int = 0,
    @Json(name = "new_roms") val newRoms: Int = 0,
    @Json(name = "identified_roms") val identifiedRoms: Int = 0,
    @Json(name = "new_files") val newFiles: Int = 0
)

@JsonClass(generateAdapter = true)
data class RomMTaskMeta(
    @Json(name = "scan_stats") val scanStats: RomMScanStats? = null
)

@JsonClass(generateAdapter = true)
data class RomMTaskResponse(
    @Json(name = "task_id") val taskId: String,
    @Json(name = "task_name") val taskName: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "meta") val meta: RomMTaskMeta? = null
)
