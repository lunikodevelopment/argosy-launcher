package com.nendo.argosy.data.sftpserver

import android.content.Context
import com.nendo.argosy.data.preferences.UserPreferencesRepository
import com.nendo.argosy.data.upload.UploadManager
import com.nendo.argosy.data.upload.security.UploadSecretKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

enum class SftpServerFailure { NO_ROM_FOLDER, START_FAILED }

sealed interface SftpServerStatus {
    data object Stopped : SftpServerStatus
    data class Running(val addresses: List<String>, val port: Int, val readOnly: Boolean) : SftpServerStatus
    data class Failed(val reason: SftpServerFailure) : SftpServerStatus
}

/**
 * Owns the embedded SFTP server. It runs only while [SftpServerService] is alive and is never started
 * from stored state, so a restart of the app or the device leaves it off until the user turns it on.
 */
@Singleton
class SftpServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadManager: UploadManager,
    private val userPreferences: UserPreferencesRepository
) {
    private val _status = MutableStateFlow<SftpServerStatus>(SftpServerStatus.Stopped)
    val status: StateFlow<SftpServerStatus> = _status.asStateFlow()

    private var server: EmbeddedSftpServer? = null

    suspend fun start() {
        synchronized(this) { if (server != null) return }
        _status.value = SftpServerStatus.Stopped
        val romRoot = userPreferences.userPreferences.first().romStoragePath
        if (romRoot.isNullOrBlank()) {
            _status.value = SftpServerStatus.Failed(SftpServerFailure.NO_ROM_FOLDER)
            return
        }
        val prefs = uploadManager.preferences.value
        val password = password()
        val config = SftpServerConfig(
            port = prefs.sftpServerPort,
            rootDir = File(romRoot),
            username = USERNAME,
            password = password,
            hostKeyFile = File(File(context.noBackupFilesDir, KEY_DIR), HOST_KEY_FILE),
            readOnly = !prefs.sftpServerWritable
        )
        val created = EmbeddedSftpServer(config)
        val port = runCatching { created.start() }.getOrNull()
        password.fill(' ')
        if (port == null) {
            runCatching { created.stop() }
            _status.value = SftpServerStatus.Failed(SftpServerFailure.START_FAILED)
            return
        }
        synchronized(this) { server = created }
        _status.value = SftpServerStatus.Running(lanAddresses(), port, config.readOnly)
    }

    fun stop() {
        val running = synchronized(this) { server.also { server = null } }
        runCatching { running?.stop() }
        if (_status.value is SftpServerStatus.Running) _status.value = SftpServerStatus.Stopped
    }

    fun password(): CharArray {
        uploadManager.secrets.get(SECRET_ID, UploadSecretKind.PASSWORD)?.let { return it }
        return generatePassword().also { uploadManager.secrets.put(SECRET_ID, UploadSecretKind.PASSWORD, it) }
    }

    fun regeneratePassword() {
        uploadManager.secrets.remove(SECRET_ID, UploadSecretKind.PASSWORD)
        password().fill(' ')
    }

    private fun generatePassword(): CharArray {
        val random = SecureRandom()
        return CharArray(PASSWORD_LENGTH) { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }
    }

    private fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is Inet4Address && LanAddressPolicy.isLan(it) && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    companion object {
        const val USERNAME = "argosy"
        private const val SECRET_ID = "sftp-server"
        private const val KEY_DIR = "sftp_server"
        private const val HOST_KEY_FILE = "hostkey"
        private const val PASSWORD_LENGTH = 16
        private const val PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }
}
