package com.nendo.argosy.ui.common

import androidx.annotation.StringRes
import com.nendo.argosy.R
import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.SftpAuthMethod
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.upload.TransferHold

@get:StringRes
val DuplicatePolicy.labelRes: Int
    get() = when (this) {
        DuplicatePolicy.SKIP -> R.string.upload_duplicate_skip
        DuplicatePolicy.KEEP_BOTH -> R.string.upload_duplicate_keep_both
        DuplicatePolicy.OVERWRITE -> R.string.upload_duplicate_overwrite
        DuplicatePolicy.CANCEL -> R.string.upload_duplicate_cancel
    }

@get:StringRes
val TransferProtocol.labelRes: Int
    get() = when (this) {
        TransferProtocol.SFTP -> R.string.upload_protocol_sftp
        TransferProtocol.SMB -> R.string.upload_protocol_smb
    }

@get:StringRes
val SftpAuthMethod.labelRes: Int
    get() = when (this) {
        SftpAuthMethod.PASSWORD -> R.string.upload_auth_password
        SftpAuthMethod.PRIVATE_KEY -> R.string.upload_auth_private_key
    }

@StringRes
fun platformTemplateLabelRes(template: String): Int =
    if (template == UploadProfile.DEFAULT_PLATFORM_TEMPLATE) {
        R.string.upload_layout_roms_platform
    } else {
        R.string.upload_layout_platform_roms
    }

@get:StringRes
val UploadErrorKind.labelRes: Int
    get() = when (this) {
        UploadErrorKind.AUTH_FAILED -> R.string.upload_error_auth_failed
        UploadErrorKind.HOST_KEY_UNKNOWN -> R.string.upload_error_host_key_unknown
        UploadErrorKind.HOST_KEY_CHANGED -> R.string.upload_error_host_key_changed
        UploadErrorKind.CONNECTION_FAILED -> R.string.upload_error_connection_failed
        UploadErrorKind.NETWORK_LOST -> R.string.upload_error_network_lost
        UploadErrorKind.TIMEOUT -> R.string.upload_error_timeout
        UploadErrorKind.PERMISSION_DENIED -> R.string.upload_error_permission_denied
        UploadErrorKind.DESTINATION_INVALID -> R.string.upload_error_destination_invalid
        UploadErrorKind.PLATFORM_FOLDER_MISSING -> R.string.upload_error_platform_folder_missing
        UploadErrorKind.SOURCE_UNREADABLE -> R.string.upload_error_source_unreadable
        UploadErrorKind.SOURCE_CHANGED -> R.string.upload_error_source_changed
        UploadErrorKind.SIZE_MISMATCH -> R.string.upload_error_size_mismatch
        UploadErrorKind.REMOTE_EXISTS -> R.string.upload_error_remote_exists
        UploadErrorKind.NO_SPACE -> R.string.upload_error_no_space
        UploadErrorKind.UNSUPPORTED -> R.string.upload_error_unsupported
        UploadErrorKind.CANCELLED -> R.string.upload_error_cancelled
        UploadErrorKind.UNKNOWN -> R.string.upload_error_unknown
    }

@get:StringRes
val UploadItemState.labelRes: Int
    get() = when (this) {
        UploadItemState.PENDING -> R.string.upload_queue_state_pending
        UploadItemState.CONNECTING -> R.string.upload_queue_state_connecting
        UploadItemState.CHECKING_DESTINATION -> R.string.upload_queue_state_checking
        UploadItemState.UPLOADING -> R.string.upload_queue_state_uploading
        UploadItemState.VERIFYING -> R.string.upload_queue_state_verifying
        UploadItemState.FINALIZING -> R.string.upload_queue_state_finalizing
        UploadItemState.COMPLETED -> R.string.upload_queue_state_completed
        UploadItemState.SKIPPED -> R.string.upload_queue_state_skipped
        UploadItemState.FAILED -> R.string.upload_queue_state_failed
        UploadItemState.CANCELLED -> R.string.upload_queue_state_cancelled
        UploadItemState.WAITING_FOR_NETWORK -> R.string.upload_queue_state_waiting
        UploadItemState.PAUSED -> R.string.upload_queue_state_paused
    }

@get:StringRes
val TransferHold.queueNoticeRes: Int?
    get() = when (this) {
        TransferHold.NONE -> null
        TransferHold.OFFLINE -> R.string.upload_queue_hold_offline
        TransferHold.METERED_NETWORK -> R.string.upload_queue_hold_wifi
        TransferHold.NOT_CHARGING -> R.string.upload_queue_hold_charger
    }
