package com.nendo.argosy.domain.model.upload

enum class SftpAuthMethod {
    PASSWORD,
    PRIVATE_KEY;

    companion object {
        fun fromString(value: String?): SftpAuthMethod = entries.firstOrNull { it.name == value } ?: PASSWORD
    }
}

/**
 * Connection settings for one RomM library host. Secrets are never part of a profile: passwords,
 * private keys and passphrases live in the encrypted upload secret store under the profile id, apart
 * from the RomM API credentials.
 */
data class UploadProfile(
    val id: String,
    val name: String,
    val protocol: TransferProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val libraryRoot: String,
    val platformTemplate: String = DEFAULT_PLATFORM_TEMPLATE,
    val sftpAuthMethod: SftpAuthMethod = SftpAuthMethod.PASSWORD,
    val smbShare: String = "",
    val smbDomain: String = "",
    val smbRequireEncryption: Boolean = false,
    val allowCreatePlatformFolders: Boolean = false
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && port in 1..65535 &&
            (protocol != TransferProtocol.SMB || smbShare.isNotBlank())

    companion object {
        const val DEFAULT_PLATFORM_TEMPLATE = "roms/{platform}"
    }
}

data class UploadSettings(
    val maxConcurrentJobs: Int = DEFAULT_CONCURRENCY,
    val duplicatePolicy: DuplicatePolicy = DuplicatePolicy.DEFAULT,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val bandwidthLimitKiBps: Int = 0,
    val inGameBandwidthLimitKiBps: Int = DEFAULT_IN_GAME_LIMIT_KIBPS,
    val scanAfterUpload: Boolean = true,
    val notifyOnCompletion: Boolean = true
) {
    companion object {
        const val DEFAULT_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 3
        const val DEFAULT_IN_GAME_LIMIT_KIBPS = 1024
    }
}
