package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.PlannedGame
import com.nendo.argosy.domain.model.upload.UploadFileTask
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadJob
import com.nendo.argosy.domain.model.upload.UploadProfile

object UploadJobs {

    fun fromPlannedGame(
        game: PlannedGame,
        profile: UploadProfile,
        planner: UploadPlanner,
        layout: RemoteLayout,
        policy: DuplicatePolicy,
        overwriteConfirmed: Boolean,
        nowMillis: Long,
        jobId: String
    ): UploadJob? {
        val directory = game.remoteGameDirectory ?: return null
        val folder = game.remotePlatformFolder ?: return null
        val tasks = game.files.mapIndexed { index, file ->
            val remote = planner.remotePathFor(game, file) ?: return null
            UploadFileTask(
                id = "$jobId-$index",
                localPath = file.absolutePath,
                relativePath = file.relativePath,
                remotePath = remote,
                sizeBytes = file.size
            )
        }
        return UploadJob(
            id = jobId,
            profileId = profile.id,
            protocol = profile.protocol,
            gameTitle = game.title,
            platformSlug = game.platform.argosySlug,
            remoteFolderName = folder.folderName,
            remoteGameDirectory = directory,
            libraryRoot = RemotePath.normalizeRoot(layout.libraryRoot, layout.protocol),
            platformDirectory = layout.platformDirectory(folder.folderName),
            layout = game.layout,
            files = tasks,
            duplicatePolicy = policy,
            overwriteConfirmed = overwriteConfirmed && policy == DuplicatePolicy.OVERWRITE,
            allowCreatePlatformFolder = profile.allowCreatePlatformFolders,
            createdAtMillis = nowMillis
        )
    }

    /**
     * State a job reloads into after the process died: work that was in flight returns to pending
     * with its byte count cleared, the remote temporary file being discarded and rewritten from the
     * start on the next attempt. Paused, waiting and finished jobs keep their state.
     */
    fun recoverAfterRestart(job: UploadJob): UploadJob {
        if (job.state.isTerminal) return job
        val files = job.files.map { file ->
            if (file.state.isTerminal && file.state != UploadItemState.FAILED) {
                file
            } else {
                file.copy(state = UploadItemState.PENDING, bytesTransferred = 0)
            }
        }
        val state = if (job.state.isActive) UploadItemState.PENDING else job.state
        return job.copy(state = state, files = files)
    }

    fun resetForRetry(job: UploadJob): UploadJob = job.copy(
        state = UploadItemState.PENDING,
        error = null,
        nextAttemptAtMillis = null,
        completedAtMillis = null,
        files = job.files.map { file ->
            when (file.state) {
                UploadItemState.COMPLETED, UploadItemState.SKIPPED -> file
                else -> file.copy(state = UploadItemState.PENDING, bytesTransferred = 0, error = null)
            }
        }
    )

    fun finalState(job: UploadJob): UploadItemState = when {
        job.files.all { it.state == UploadItemState.SKIPPED } -> UploadItemState.SKIPPED
        job.files.all { it.state == UploadItemState.COMPLETED || it.state == UploadItemState.SKIPPED } ->
            UploadItemState.COMPLETED
        job.files.any { it.state == UploadItemState.CANCELLED } -> UploadItemState.CANCELLED
        else -> UploadItemState.FAILED
    }
}
