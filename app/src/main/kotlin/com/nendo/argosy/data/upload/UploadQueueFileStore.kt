package com.nendo.argosy.data.upload

import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.GameLayout
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadError
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadFileTask
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadJob
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File

@JsonClass(generateAdapter = true)
data class UploadFileRecord(
    val id: String,
    val localPath: String,
    val relativePath: String,
    val remotePath: String,
    val sizeBytes: Long,
    val state: String,
    val finalRemotePath: String?,
    val attempts: Int,
    val errorKind: String?,
    val errorDetail: String?
)

@JsonClass(generateAdapter = true)
data class UploadJobRecord(
    val id: String,
    val profileId: String,
    val protocol: String,
    val gameTitle: String,
    val platformSlug: String?,
    val remoteFolderName: String?,
    val remoteGameDirectory: String,
    val libraryRoot: String,
    val platformDirectory: String?,
    val layout: String,
    val files: List<UploadFileRecord>,
    val duplicatePolicy: String,
    val overwriteConfirmed: Boolean,
    val allowCreatePlatformFolder: Boolean,
    val createdAtMillis: Long,
    val state: String,
    val errorKind: String?,
    val errorDetail: String?,
    val attempts: Int,
    val nextAttemptAtMillis: Long?,
    val completedAtMillis: Long?
)

@JsonClass(generateAdapter = true)
data class UploadQueueDocument(
    val version: Int,
    val jobs: List<UploadJobRecord>
)

/**
 * Upload queue persisted as one JSON document, replaced atomically through a sibling temporary file
 * and a rename. A missing or unreadable document loads as an empty queue; a document naming an
 * unknown protocol drops only the affected job.
 */
class UploadQueueFileStore(
    private val file: File,
    moshi: Moshi
) : UploadJobStore {
    private val adapter = moshi.adapter(UploadQueueDocument::class.java)

    @Synchronized
    override fun load(): List<UploadJob> {
        if (!file.exists()) return emptyList()
        val document = runCatching { adapter.fromJson(file.readText()) }.getOrNull() ?: return emptyList()
        return document.jobs.mapNotNull { it.toModel() }
    }

    @Synchronized
    override fun save(jobs: List<UploadJob>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(adapter.toJson(UploadQueueDocument(VERSION, jobs.map { it.toRecord() })))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) error("could not replace ${file.name}")
        }
    }

    private fun UploadJob.toRecord() = UploadJobRecord(
        id = id,
        profileId = profileId,
        protocol = protocol.name,
        gameTitle = gameTitle,
        platformSlug = platformSlug,
        remoteFolderName = remoteFolderName,
        remoteGameDirectory = remoteGameDirectory,
        libraryRoot = libraryRoot,
        platformDirectory = platformDirectory,
        layout = layout.name,
        files = files.map { it.toRecord() },
        duplicatePolicy = duplicatePolicy.name,
        overwriteConfirmed = overwriteConfirmed,
        allowCreatePlatformFolder = allowCreatePlatformFolder,
        createdAtMillis = createdAtMillis,
        state = state.name,
        errorKind = error?.kind?.name,
        errorDetail = error?.detail,
        attempts = attempts,
        nextAttemptAtMillis = nextAttemptAtMillis,
        completedAtMillis = completedAtMillis
    )

    private fun UploadFileTask.toRecord() = UploadFileRecord(
        id = id,
        localPath = localPath,
        relativePath = relativePath,
        remotePath = remotePath,
        sizeBytes = sizeBytes,
        state = state.name,
        finalRemotePath = finalRemotePath,
        attempts = attempts,
        errorKind = error?.kind?.name,
        errorDetail = error?.detail
    )

    private fun UploadJobRecord.toModel(): UploadJob? {
        val protocol = TransferProtocol.fromString(protocol) ?: return null
        return UploadJob(
            id = id,
            profileId = profileId,
            protocol = protocol,
            gameTitle = gameTitle,
            platformSlug = platformSlug,
            remoteFolderName = remoteFolderName,
            remoteGameDirectory = remoteGameDirectory,
            libraryRoot = libraryRoot,
            platformDirectory = platformDirectory,
            layout = GameLayout.entries.firstOrNull { it.name == layout } ?: GameLayout.SINGLE_FILE,
            files = files.map { it.toModel() },
            duplicatePolicy = DuplicatePolicy.fromString(duplicatePolicy),
            overwriteConfirmed = overwriteConfirmed,
            allowCreatePlatformFolder = allowCreatePlatformFolder,
            createdAtMillis = createdAtMillis,
            state = UploadItemState.fromString(state),
            error = errorKind?.let { UploadError(UploadErrorKind.fromString(it), errorDetail) },
            attempts = attempts,
            nextAttemptAtMillis = nextAttemptAtMillis,
            completedAtMillis = completedAtMillis
        )
    }

    private fun UploadFileRecord.toModel() = UploadFileTask(
        id = id,
        localPath = localPath,
        relativePath = relativePath,
        remotePath = remotePath,
        sizeBytes = sizeBytes,
        state = UploadItemState.fromString(state),
        finalRemotePath = finalRemotePath,
        attempts = attempts,
        error = errorKind?.let { UploadError(UploadErrorKind.fromString(it), errorDetail) }
    )

    companion object {
        const val VERSION = 1
    }
}
