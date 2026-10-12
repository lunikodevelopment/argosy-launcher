package com.nendo.argosy.data.upload

import android.content.Context
import android.os.Process
import com.nendo.argosy.data.emulator.PlaySessionTracker
import com.nendo.argosy.data.local.dao.PlatformDao
import com.nendo.argosy.data.platform.PlatformDefinitions
import com.nendo.argosy.data.preferences.UploadPreferences
import com.nendo.argosy.data.preferences.UploadPreferencesRepository
import com.nendo.argosy.data.storage.FileAccessLayer
import com.nendo.argosy.data.transfer.TransferConditionMonitor
import com.nendo.argosy.data.upload.security.HostKeyStore
import com.nendo.argosy.data.upload.security.KeystoreSecretCipher
import com.nendo.argosy.data.upload.security.UploadSecretKind
import com.nendo.argosy.data.upload.security.UploadSecretStore
import com.nendo.argosy.data.upload.transfer.HostKeyRejectedException
import com.nendo.argosy.data.upload.transfer.SftpTransferClient
import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.HostKeyFingerprint
import com.nendo.argosy.domain.model.upload.PlannedGame
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadError
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadJob
import com.nendo.argosy.domain.model.upload.UploadProfile
import com.nendo.argosy.domain.upload.BandwidthLimiter
import com.nendo.argosy.domain.upload.LocalSelection
import com.nendo.argosy.domain.upload.PlatformResolver
import com.nendo.argosy.domain.upload.RemoteLayout
import com.nendo.argosy.domain.upload.RemotePath
import com.nendo.argosy.domain.upload.RemotePlatformFolderResolver
import com.nendo.argosy.domain.upload.TransferException
import com.nendo.argosy.domain.upload.TransferHold
import com.nendo.argosy.domain.upload.UploadJobs
import com.nendo.argosy.domain.upload.UploadPlan
import com.nendo.argosy.domain.upload.UploadPlanner
import com.nendo.argosy.util.SafeCoroutineScope
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConnectionTestResult {
    data class Success(val platformFolders: Int, val freeBytes: Long?) : ConnectionTestResult
    data class HostKeyUntrusted(
        val presented: HostKeyFingerprint,
        val expected: HostKeyFingerprint?
    ) : ConnectionTestResult
    data class Failure(val error: UploadError) : ConnectionTestResult
}

/**
 * App-wide entry to ROM uploads: plans selections against the user's mappings and the synced RomM
 * platform folders, queues jobs on the persistent [UploadEngine], and feeds it connectivity,
 * concurrency and bandwidth limits. Lives for the process; transfers never depend on an activity.
 */
@Singleton
class UploadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: UploadPreferencesRepository,
    private val fileAccess: FileAccessLayer,
    private val platformDao: PlatformDao,
    private val conditionMonitor: TransferConditionMonitor,
    private val playSessionTracker: PlaySessionTracker,
    moshi: Moshi
) {
    private val scope = SafeCoroutineScope(Dispatchers.IO, "UploadManager")
    private val uploadDir = File(context.filesDir, UPLOAD_DIR)

    val secrets = UploadSecretStore(File(context.noBackupFilesDir, SECRETS_DIR), KeystoreSecretCipher())
    val hostKeys = HostKeyStore(File(uploadDir, HOST_KEYS_FILE), moshi)

    val preferences: StateFlow<UploadPreferences> = preferencesRepository.preferences
        .stateIn(scope, SharingStarted.Eagerly, UploadPreferences())

    private val bandwidthBytesPerSecond = MutableStateFlow(0L)
    private val _hold = MutableStateFlow(TransferHold.NONE)
    val hold: StateFlow<TransferHold> = _hold.asStateFlow()

    private val clientFactory = UploadClientFactory({ preferences.value.profiles }, secrets, hostKeys)
    private val localTree = FileAccessLocalTree(fileAccess)

    val engine = UploadEngine(
        scope = scope,
        dispatcher = Dispatchers.IO,
        store = UploadQueueFileStore(File(uploadDir, QUEUE_FILE), moshi),
        clientFactory = clientFactory,
        localSource = localTree,
        limiter = BandwidthLimiter({ bandwidthBytesPerSecond.value }),
        workerScope = ::atBackgroundPriority
    )

    private fun atBackgroundPriority(block: () -> Unit) {
        val tid = Process.myTid()
        val previous = runCatching { Process.getThreadPriority(tid) }.getOrNull()
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            block()
        } finally {
            previous?.let { runCatching { Process.setThreadPriority(it) } }
        }
    }

    val jobs: StateFlow<List<UploadJob>> get() = engine.jobs

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        conditionMonitor.start()
        scope.launch {
            preferencesRepository.preferences.first()
            engine.start()
            observeLimits()
        }
    }

    private fun observeLimits() {
        scope.launch {
            combine(preferences, conditionMonitor.conditions, playSessionTracker.hasActiveSession) { prefs, conditions, inGame ->
                val settings = prefs.settings
                engine.maxConcurrent = if (inGame) 1 else settings.maxConcurrentJobs
                val kibps = if (inGame && settings.inGameBandwidthLimitKiBps > 0) {
                    if (settings.bandwidthLimitKiBps > 0) {
                        minOf(settings.bandwidthLimitKiBps, settings.inGameBandwidthLimitKiBps)
                    } else {
                        settings.inGameBandwidthLimitKiBps
                    }
                } else {
                    settings.bandwidthLimitKiBps
                }
                bandwidthBytesPerSecond.value = kibps * BYTES_PER_KIB
                TransferHold.of(conditions, settings.wifiOnly, settings.chargingOnly)
            }.collect { hold ->
                _hold.value = hold
                engine.setNetworkAvailable(hold == TransferHold.NONE)
            }
        }
    }

    suspend fun plan(selections: List<LocalSelection>, profile: UploadProfile): UploadPlan =
        withContext(Dispatchers.IO) { plannerFor(profile).plan(selections) }

    suspend fun plannerFor(profile: UploadProfile): UploadPlanner {
        val prefs = preferences.value
        val rommFolders = platformDao.observeAllPlatforms().first()
            .mapNotNull { entity ->
                entity.fsSlug?.takeIf { it.isNotBlank() }?.let { PlatformDefinitions.getCanonicalSlug(entity.slug) to it }
            }
            .toMap()
        return UploadPlanner(
            tree = localTree,
            platformResolver = PlatformResolver(PlatformDefinitionsCatalog, prefs.localFolderMappings),
            folderResolver = RemotePlatformFolderResolver(prefs.remoteFolderMappings, rommFolders),
            layout = layoutFor(profile)
        )
    }

    fun layoutFor(profile: UploadProfile) = RemoteLayout(profile.libraryRoot, profile.platformTemplate, profile.protocol)

    suspend fun enqueue(
        games: List<PlannedGame>,
        profile: UploadProfile,
        policy: DuplicatePolicy,
        overwriteConfirmed: Boolean
    ): Int {
        val planner = plannerFor(profile)
        val layout = layoutFor(profile)
        val now = System.currentTimeMillis()
        val jobs = games.mapNotNull { game ->
            UploadJobs.fromPlannedGame(
                game = game,
                profile = profile,
                planner = planner,
                layout = layout,
                policy = policy,
                overwriteConfirmed = overwriteConfirmed,
                nowMillis = now,
                jobId = UUID.randomUUID().toString()
            )
        }
        engine.enqueue(jobs)
        return jobs.size
    }

    suspend fun testConnection(profile: UploadProfile): ConnectionTestResult = withContext(Dispatchers.IO) {
        val client = try {
            clientFactory.clientFor(profile, trustUnknownHostForProbe = true)
        } catch (e: TransferException) {
            return@withContext ConnectionTestResult.Failure(e.error)
        }
        try {
            client.use {
                it.connect()
                val layout = layoutFor(profile)
                val root = RemotePath.normalizeRoot(profile.libraryRoot, profile.protocol)
                val rootStat = it.stat(root)
                if (rootStat == null || !rootStat.isDirectory) {
                    return@withContext ConnectionTestResult.Failure(UploadError(UploadErrorKind.DESTINATION_INVALID, root))
                }
                val platformParent = RemotePath.parent(layout.platformDirectory(PROBE_FOLDER)) ?: root
                val folders = runCatching { it.list(platformParent).count { entry -> entry.isDirectory } }.getOrDefault(0)
                ConnectionTestResult.Success(folders, it.availableBytes(root))
            }
        } catch (e: TransferException) {
            val rejection = (client as? SftpTransferClient)?.lastHostKeyRejection
            if (rejection != null) {
                ConnectionTestResult.HostKeyUntrusted(rejection.presented, rejection.expected)
            } else {
                ConnectionTestResult.Failure(e.error)
            }
        } catch (e: HostKeyRejectedException) {
            ConnectionTestResult.HostKeyUntrusted(e.presented, e.expected)
        } catch (e: Exception) {
            ConnectionTestResult.Failure(UploadError(UploadErrorKind.UNKNOWN, e.javaClass.simpleName))
        }
    }

    fun trustHostKey(profile: UploadProfile, fingerprint: HostKeyFingerprint) {
        require(profile.protocol == TransferProtocol.SFTP)
        hostKeys.trust(profile.host.trim(), profile.port, fingerprint)
    }

    suspend fun saveProfile(profile: UploadProfile, password: CharArray?, privateKey: CharArray?, passphrase: CharArray?) {
        withContext(Dispatchers.IO) {
            password?.let { secrets.put(profile.id, UploadSecretKind.PASSWORD, it) }
            privateKey?.let { secrets.put(profile.id, UploadSecretKind.PRIVATE_KEY, it) }
            passphrase?.let { secrets.put(profile.id, UploadSecretKind.KEY_PASSPHRASE, it) }
        }
        preferencesRepository.saveProfile(profile)
    }

    suspend fun deleteProfile(profileId: String) {
        val profile = preferences.value.profiles.firstOrNull { it.id == profileId }
        preferencesRepository.deleteProfile(profileId)
        withContext(Dispatchers.IO) {
            secrets.removeProfile(profileId)
            if (profile != null && profile.protocol == TransferProtocol.SFTP) {
                val sharedHost = preferences.value.profiles.any { it.id != profileId && it.host == profile.host && it.port == profile.port }
                if (!sharedHost) hostKeys.forget(profile.host.trim(), profile.port)
            }
        }
    }

    fun newProfileId(): String = UUID.randomUUID().toString()

    suspend fun readPrivateKeyFile(path: String): CharArray? = withContext(Dispatchers.IO) {
        if (fileAccess.length(path) > MAX_KEY_FILE_BYTES) return@withContext null
        val bytes = fileAccess.readBytes(path) ?: return@withContext null
        try {
            val chars = Charsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes))
            CharArray(chars.remaining()).also { chars.get(it) }.takeIf { key -> key.containsMarker(KEY_MARKER) }
        } finally {
            bytes.fill(0)
        }
    }

    fun hasSecret(profileId: String, kind: UploadSecretKind): Boolean = secrets.has(profileId, kind)

    private fun CharArray.containsMarker(marker: String): Boolean =
        (0..size - marker.length).any { start -> marker.indices.all { this[start + it] == marker[it] } }

    companion object {
        private const val UPLOAD_DIR = "uploads"
        private const val SECRETS_DIR = "upload_secrets"
        private const val QUEUE_FILE = "queue.json"
        private const val HOST_KEYS_FILE = "host_keys.json"
        private const val PROBE_FOLDER = "probe"
        private const val BYTES_PER_KIB = 1024L
        private const val MAX_KEY_FILE_BYTES = 64 * 1024L
        private const val KEY_MARKER = "PRIVATE KEY"
    }
}
