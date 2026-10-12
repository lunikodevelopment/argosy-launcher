package com.nendo.argosy.domain.model.upload

enum class TransferProtocol {
    SFTP,
    SMB;

    val defaultPort: Int
        get() = when (this) {
            SFTP -> 22
            SMB -> 445
        }

    companion object {
        fun fromString(value: String?): TransferProtocol? = entries.firstOrNull { it.name == value }
    }
}

enum class DuplicatePolicy {
    SKIP,
    OVERWRITE,
    KEEP_BOTH,
    CANCEL;

    companion object {
        val DEFAULT = SKIP

        fun fromString(value: String?): DuplicatePolicy = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

enum class UploadItemState {
    PENDING,
    CONNECTING,
    CHECKING_DESTINATION,
    UPLOADING,
    VERIFYING,
    FINALIZING,
    COMPLETED,
    SKIPPED,
    FAILED,
    CANCELLED,
    WAITING_FOR_NETWORK,
    PAUSED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == SKIPPED || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = this == CONNECTING || this == CHECKING_DESTINATION || this == UPLOADING ||
            this == VERIFYING || this == FINALIZING

    companion object {
        fun fromString(value: String?): UploadItemState = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

enum class UploadErrorKind(val retryable: Boolean) {
    AUTH_FAILED(false),
    HOST_KEY_UNKNOWN(false),
    HOST_KEY_CHANGED(false),
    CONNECTION_FAILED(true),
    NETWORK_LOST(true),
    TIMEOUT(true),
    PERMISSION_DENIED(false),
    DESTINATION_INVALID(false),
    PLATFORM_FOLDER_MISSING(false),
    SOURCE_UNREADABLE(false),
    SOURCE_CHANGED(false),
    SIZE_MISMATCH(true),
    REMOTE_EXISTS(false),
    NO_SPACE(false),
    UNSUPPORTED(false),
    CANCELLED(false),
    UNKNOWN(true);

    companion object {
        fun fromString(value: String?): UploadErrorKind = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

data class UploadError(
    val kind: UploadErrorKind,
    val detail: String? = null
)

enum class PlatformCertainty {
    USER_MAPPING,
    MANUAL,
    GAME_METADATA,
    SOURCE_FOLDER,
    EXTENSION,
    UNKNOWN;

    val needsConfirmation: Boolean
        get() = this == EXTENSION || this == UNKNOWN
}

data class PlatformMatch(
    val argosySlug: String?,
    val certainty: PlatformCertainty
) {
    val needsConfirmation: Boolean
        get() = argosySlug == null || certainty.needsConfirmation

    companion object {
        val UNKNOWN = PlatformMatch(null, PlatformCertainty.UNKNOWN)
    }
}

enum class RemoteFolderSource {
    USER_MAPPING,
    ROMM_LIBRARY,
    ROMM_DEFAULT
}

data class RemotePlatformFolder(
    val folderName: String,
    val source: RemoteFolderSource
)

data class LocalSourceFile(
    val absolutePath: String,
    val relativePath: String,
    val size: Long
)

enum class GameLayout {
    SINGLE_FILE,
    FILE_SET,
    FOLDER
}

data class PlannedGame(
    val id: String,
    val title: String,
    val layout: GameLayout,
    val platform: PlatformMatch,
    val sourceDirectory: String,
    val files: List<LocalSourceFile>,
    val remotePlatformFolder: RemotePlatformFolder?,
    val remoteGameDirectory: String?
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val isPlaceable: Boolean get() = remoteGameDirectory != null
}

data class UploadFileTask(
    val id: String,
    val localPath: String,
    val relativePath: String,
    val remotePath: String,
    val sizeBytes: Long,
    val state: UploadItemState = UploadItemState.PENDING,
    val bytesTransferred: Long = 0L,
    val finalRemotePath: String? = null,
    val attempts: Int = 0,
    val error: UploadError? = null
)

data class UploadJob(
    val id: String,
    val profileId: String,
    val protocol: TransferProtocol,
    val gameTitle: String,
    val platformSlug: String?,
    val remoteFolderName: String?,
    val remoteGameDirectory: String,
    val layout: GameLayout,
    val files: List<UploadFileTask>,
    val duplicatePolicy: DuplicatePolicy,
    val overwriteConfirmed: Boolean,
    val allowCreatePlatformFolder: Boolean,
    val createdAtMillis: Long,
    val state: UploadItemState = UploadItemState.PENDING,
    val error: UploadError? = null,
    val nextAttemptAtMillis: Long? = null,
    val completedAtMillis: Long? = null
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val transferredBytes: Long get() = files.sumOf { it.bytesTransferred }
    val completedFiles: Int get() = files.count { it.state == UploadItemState.COMPLETED }
    val skippedFiles: Int get() = files.count { it.state == UploadItemState.SKIPPED }
    val failedFiles: Int get() = files.count { it.state == UploadItemState.FAILED }
}

data class HostKeyFingerprint(
    val keyType: String,
    val sha256: String
)

enum class HostKeyProblem {
    UNKNOWN,
    CHANGED
}
