package com.nendo.argosy.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.SftpAuthMethod
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.model.upload.UploadSettings
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
data class UploadProfileRecord(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val libraryRoot: String,
    val platformTemplate: String,
    val sftpAuthMethod: String,
    val smbShare: String,
    val smbDomain: String,
    val smbRequireEncryption: Boolean,
    val allowCreatePlatformFolders: Boolean
)

data class UploadPreferences(
    val settings: UploadSettings = UploadSettings(),
    val profiles: List<UploadProfile> = emptyList(),
    val defaultProfileId: String? = null,
    val localFolderMappings: Map<String, String> = emptyMap(),
    val remoteFolderMappings: Map<String, String> = emptyMap(),
    val sftpServerPort: Int = DEFAULT_SFTP_SERVER_PORT,
    val sftpServerWritable: Boolean = false
) {
    val defaultProfile: UploadProfile?
        get() = profiles.firstOrNull { it.id == defaultProfileId } ?: profiles.firstOrNull()
}

const val DEFAULT_SFTP_SERVER_PORT = 2222
const val MIN_SFTP_SERVER_PORT = 1024
private const val MAX_PORT = 65535

@Singleton
class UploadPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    moshi: Moshi
) {
    private object Keys {
        val MAX_CONCURRENT = intPreferencesKey("upload_max_concurrent")
        val DUPLICATE_POLICY = stringPreferencesKey("upload_duplicate_policy")
        val WIFI_ONLY = booleanPreferencesKey("upload_wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("upload_charging_only")
        val BANDWIDTH_LIMIT_KIBPS = intPreferencesKey("upload_bandwidth_limit_kibps")
        val IN_GAME_LIMIT_KIBPS = intPreferencesKey("upload_in_game_limit_kibps")
        val SCAN_AFTER_UPLOAD = booleanPreferencesKey("upload_scan_after_upload")
        val NOTIFY_ON_COMPLETION = booleanPreferencesKey("upload_notify_on_completion")
        val PROFILES = stringPreferencesKey("upload_profiles_json")
        val DEFAULT_PROFILE = stringPreferencesKey("upload_default_profile")
        val LOCAL_FOLDER_MAPPINGS = stringPreferencesKey("upload_local_folder_mappings_json")
        val REMOTE_FOLDER_MAPPINGS = stringPreferencesKey("upload_remote_folder_mappings_json")
        val SFTP_SERVER_PORT = intPreferencesKey("sftp_server_port")
        val SFTP_SERVER_WRITABLE = booleanPreferencesKey("sftp_server_writable")
    }

    private val profilesAdapter = moshi.adapter<List<UploadProfileRecord>>(
        Types.newParameterizedType(List::class.java, UploadProfileRecord::class.java)
    )
    private val mapAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    val preferences: Flow<UploadPreferences> = dataStore.data.map { prefs ->
        UploadPreferences(
            settings = UploadSettings(
                maxConcurrentJobs = (prefs[Keys.MAX_CONCURRENT] ?: UploadSettings.DEFAULT_CONCURRENCY)
                    .coerceIn(1, UploadSettings.MAX_CONCURRENCY),
                duplicatePolicy = DuplicatePolicy.fromString(prefs[Keys.DUPLICATE_POLICY]),
                wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
                chargingOnly = prefs[Keys.CHARGING_ONLY] ?: false,
                bandwidthLimitKiBps = (prefs[Keys.BANDWIDTH_LIMIT_KIBPS] ?: 0).coerceAtLeast(0),
                inGameBandwidthLimitKiBps = (prefs[Keys.IN_GAME_LIMIT_KIBPS] ?: UploadSettings.DEFAULT_IN_GAME_LIMIT_KIBPS)
                    .coerceAtLeast(0),
                scanAfterUpload = prefs[Keys.SCAN_AFTER_UPLOAD] ?: true,
                notifyOnCompletion = prefs[Keys.NOTIFY_ON_COMPLETION] ?: true
            ),
            profiles = decodeProfiles(prefs[Keys.PROFILES]),
            defaultProfileId = prefs[Keys.DEFAULT_PROFILE],
            localFolderMappings = decodeMap(prefs[Keys.LOCAL_FOLDER_MAPPINGS]),
            remoteFolderMappings = decodeMap(prefs[Keys.REMOTE_FOLDER_MAPPINGS]),
            sftpServerPort = (prefs[Keys.SFTP_SERVER_PORT] ?: DEFAULT_SFTP_SERVER_PORT).coerceIn(MIN_SFTP_SERVER_PORT, MAX_PORT),
            sftpServerWritable = prefs[Keys.SFTP_SERVER_WRITABLE] ?: false
        )
    }

    suspend fun current(): UploadPreferences = preferences.first()

    suspend fun setMaxConcurrent(value: Int) {
        dataStore.edit { it[Keys.MAX_CONCURRENT] = value.coerceIn(1, UploadSettings.MAX_CONCURRENCY) }
    }

    suspend fun setDuplicatePolicy(policy: DuplicatePolicy) {
        dataStore.edit { it[Keys.DUPLICATE_POLICY] = policy.name }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setChargingOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.CHARGING_ONLY] = enabled }
    }

    suspend fun setBandwidthLimitKiBps(value: Int) {
        dataStore.edit { it[Keys.BANDWIDTH_LIMIT_KIBPS] = value.coerceAtLeast(0) }
    }

    suspend fun setInGameBandwidthLimitKiBps(value: Int) {
        dataStore.edit { it[Keys.IN_GAME_LIMIT_KIBPS] = value.coerceAtLeast(0) }
    }

    suspend fun setScanAfterUpload(enabled: Boolean) {
        dataStore.edit { it[Keys.SCAN_AFTER_UPLOAD] = enabled }
    }

    suspend fun setNotifyOnCompletion(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFY_ON_COMPLETION] = enabled }
    }

    suspend fun setSftpServerPort(port: Int) {
        dataStore.edit { it[Keys.SFTP_SERVER_PORT] = port.coerceIn(MIN_SFTP_SERVER_PORT, MAX_PORT) }
    }

    suspend fun setSftpServerWritable(enabled: Boolean) {
        dataStore.edit { it[Keys.SFTP_SERVER_WRITABLE] = enabled }
    }

    suspend fun saveProfile(profile: UploadProfile) {
        dataStore.edit { prefs ->
            val others = decodeProfiles(prefs[Keys.PROFILES]).filterNot { it.id == profile.id }
            prefs[Keys.PROFILES] = profilesAdapter.toJson((others + profile).map { it.toRecord() })
            if (prefs[Keys.DEFAULT_PROFILE] == null) prefs[Keys.DEFAULT_PROFILE] = profile.id
        }
    }

    suspend fun deleteProfile(profileId: String) {
        dataStore.edit { prefs ->
            val remaining = decodeProfiles(prefs[Keys.PROFILES]).filterNot { it.id == profileId }
            prefs[Keys.PROFILES] = profilesAdapter.toJson(remaining.map { it.toRecord() })
            if (prefs[Keys.DEFAULT_PROFILE] == profileId) {
                remaining.firstOrNull()?.let { prefs[Keys.DEFAULT_PROFILE] = it.id } ?: prefs.remove(Keys.DEFAULT_PROFILE)
            }
        }
    }

    suspend fun setDefaultProfile(profileId: String) {
        dataStore.edit { it[Keys.DEFAULT_PROFILE] = profileId }
    }

    suspend fun setLocalFolderMapping(localPath: String, platformSlug: String?) {
        editMap(Keys.LOCAL_FOLDER_MAPPINGS, localPath.trimEnd('/'), platformSlug)
    }

    suspend fun setRemoteFolderMapping(platformSlug: String, remoteFolder: String?) {
        editMap(Keys.REMOTE_FOLDER_MAPPINGS, platformSlug.lowercase(), remoteFolder?.trim()?.trim('/'))
    }

    private suspend fun editMap(key: Preferences.Key<String>, entryKey: String, value: String?) {
        dataStore.edit { prefs ->
            val current = decodeMap(prefs[key]).toMutableMap()
            if (value.isNullOrBlank()) current.remove(entryKey) else current[entryKey] = value
            prefs[key] = mapAdapter.toJson(current)
        }
    }

    private fun decodeProfiles(json: String?): List<UploadProfile> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { profilesAdapter.fromJson(json) }.getOrNull().orEmpty().mapNotNull { it.toModel() }
    }

    private fun decodeMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { mapAdapter.fromJson(json) }.getOrNull().orEmpty()
    }

    private fun UploadProfile.toRecord() = UploadProfileRecord(
        id = id,
        name = name,
        protocol = protocol.name,
        host = host,
        port = port,
        username = username,
        libraryRoot = libraryRoot,
        platformTemplate = platformTemplate,
        sftpAuthMethod = sftpAuthMethod.name,
        smbShare = smbShare,
        smbDomain = smbDomain,
        smbRequireEncryption = smbRequireEncryption,
        allowCreatePlatformFolders = allowCreatePlatformFolders
    )

    private fun UploadProfileRecord.toModel(): UploadProfile? {
        val protocol = TransferProtocol.fromString(protocol) ?: return null
        return UploadProfile(
            id = id,
            name = name,
            protocol = protocol,
            host = host,
            port = port,
            username = username,
            libraryRoot = libraryRoot,
            platformTemplate = platformTemplate,
            sftpAuthMethod = SftpAuthMethod.fromString(sftpAuthMethod),
            smbShare = smbShare,
            smbDomain = smbDomain,
            smbRequireEncryption = smbRequireEncryption,
            allowCreatePlatformFolders = allowCreatePlatformFolders
        )
    }
}
