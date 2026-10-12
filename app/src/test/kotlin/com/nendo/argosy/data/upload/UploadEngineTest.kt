package com.nendo.argosy.data.upload

import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.GameLayout
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadFileTask
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadJob
import com.nendo.argosy.domain.upload.BandwidthLimiter
import com.nendo.argosy.domain.upload.FakeRemoteTransferClient
import com.nendo.argosy.domain.upload.RetryPolicy
import com.nendo.argosy.domain.upload.TransferException
import com.nendo.argosy.domain.upload.UploadExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class UploadEngineTest {

    private val platformDir = "/lib/roms/psx"
    private val cue = "FILE \"Game (Track 1).bin\" BINARY".toByteArray()
    private val bin = ByteArray(64) { it.toByte() }
    private val local = mapOf("/sd/psx/Game.cue" to cue, "/sd/psx/Game (Track 1).bin" to bin)

    private class MemoryStore(var saved: List<UploadJob> = emptyList()) : UploadJobStore {
        var saves = 0
        override fun load() = saved
        override fun save(jobs: List<UploadJob>) {
            saves++
            saved = jobs
        }
    }

    private fun job(id: String = "job-1", state: UploadItemState = UploadItemState.PENDING) = UploadJob(
        id = id,
        profileId = "p1",
        protocol = TransferProtocol.SFTP,
        gameTitle = "Game",
        platformSlug = "psx",
        remoteFolderName = "psx",
        remoteGameDirectory = platformDir,
        libraryRoot = "/lib",
        platformDirectory = platformDir,
        layout = GameLayout.FILE_SET,
        files = listOf(
            UploadFileTask("$id-0", "/sd/psx/Game.cue", "Game.cue", "$platformDir/Game.cue", cue.size.toLong()),
            UploadFileTask(
                "$id-1", "/sd/psx/Game (Track 1).bin", "Game (Track 1).bin",
                "$platformDir/Game (Track 1).bin", bin.size.toLong()
            )
        ),
        duplicatePolicy = DuplicatePolicy.SKIP,
        overwriteConfirmed = false,
        allowCreatePlatformFolder = false,
        createdAtMillis = 0,
        state = state
    )

    private fun TestScope.engine(
        client: FakeRemoteTransferClient,
        store: MemoryStore = MemoryStore(),
        open: (String) -> InputStream? = { path -> local[path]?.let(::ByteArrayInputStream) },
        factoryError: TransferException? = null
    ): UploadEngine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return UploadEngine(
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatcher = dispatcher,
            store = store,
            clientFactory = { factoryError?.let { throw it } ?: client },
            localSource = { open(it) },
            limiter = BandwidthLimiter({ 0L }),
            retryPolicy = RetryPolicy(maxAttempts = 3, baseDelayMillis = 1_000, maxDelayMillis = 60_000),
            clock = { testScheduler.currentTime }
        )
    }

    private fun noTemps(client: FakeRemoteTransferClient) =
        client.files.keys.none { it.endsWith(UploadExecutor.TEMP_EXTENSION) }

    private fun UploadEngine.only() = jobs.value.single()

    @Test
    fun `a queued multi-file set uploads every file and is persisted as completed`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val store = MemoryStore()
        val engine = engine(client, store)
        engine.start()
        engine.enqueue(listOf(job()))
        advanceUntilIdle()
        assertEquals(UploadItemState.COMPLETED, engine.only().state)
        assertArrayEquals(cue, client.files["$platformDir/Game.cue"])
        assertArrayEquals(bin, client.files["$platformDir/Game (Track 1).bin"])
        assertEquals(UploadItemState.COMPLETED, store.saved.single().state)
        assertTrue(noTemps(client))
    }

    @Test
    fun `a retryable failure waits for the backoff then succeeds`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir).apply { failNextUploads = 1 }
        val engine = engine(client)
        engine.start()
        engine.enqueue(listOf(job()))
        runCurrent()
        assertEquals(UploadItemState.PENDING, engine.only().state)
        assertEquals(UploadErrorKind.NETWORK_LOST, engine.only().error?.kind)
        assertEquals(1_000L, engine.only().nextAttemptAtMillis)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(UploadItemState.PENDING, engine.only().state)
        advanceUntilIdle()
        assertEquals(UploadItemState.COMPLETED, engine.only().state)
        assertEquals(2, engine.only().attempts)
        assertTrue(noTemps(client))
    }

    @Test
    fun `retries stop after the attempt limit and the job fails`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir).apply { failNextUploads = 10 }
        val engine = engine(client)
        engine.start()
        engine.enqueue(listOf(job()))
        advanceUntilIdle()
        assertEquals(UploadItemState.FAILED, engine.only().state)
        assertEquals(3, engine.only().attempts)
        assertFalse(client.files.containsKey("$platformDir/Game.cue"))
    }

    @Test
    fun `an authentication failure is not retried`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val engine = engine(client, factoryError = TransferException(UploadErrorKind.AUTH_FAILED))
        engine.start()
        engine.enqueue(listOf(job()))
        advanceUntilIdle()
        assertEquals(UploadItemState.FAILED, engine.only().state)
        assertEquals(UploadErrorKind.AUTH_FAILED, engine.only().error?.kind)
        assertEquals(1, engine.only().attempts)
    }

    @Test
    fun `jobs wait while offline and run once the network returns`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val engine = engine(client)
        engine.start()
        engine.setNetworkAvailable(false)
        engine.enqueue(listOf(job()))
        advanceUntilIdle()
        assertTrue(client.operations.isEmpty())
        engine.setNetworkAvailable(true)
        advanceUntilIdle()
        assertEquals(UploadItemState.COMPLETED, engine.only().state)
    }

    @Test
    fun `losing the network mid-transfer parks the job and leaves no partial file`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        lateinit var engine: UploadEngine
        var dropped = false
        engine = engine(client, open = { path ->
            val bytes = local[path] ?: return@engine null
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (path.endsWith(".bin") && !dropped) {
                        dropped = true
                        engine.setNetworkAvailable(false)
                    }
                    return super.read(b, off, len)
                }
            }
        })
        engine.start()
        engine.enqueue(listOf(job()))
        advanceUntilIdle()
        assertEquals(UploadItemState.WAITING_FOR_NETWORK, engine.only().state)
        assertEquals(UploadItemState.COMPLETED, engine.only().files[0].state)
        assertEquals(UploadItemState.PENDING, engine.only().files[1].state)
        assertFalse(client.files.containsKey("$platformDir/Game (Track 1).bin"))
        assertTrue(noTemps(client))

        engine.setNetworkAvailable(true)
        advanceUntilIdle()
        assertEquals(UploadItemState.COMPLETED, engine.only().state)
        assertEquals(1, client.operations.count { it.startsWith("upload") && it.contains("Game.cue") })
        assertArrayEquals(bin, client.files["$platformDir/Game (Track 1).bin"])
    }

    @Test
    fun `pausing mid-transfer discards the partial upload and resume restarts it`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        lateinit var engine: UploadEngine
        var paused = false
        engine = engine(client, open = { path ->
            val bytes = local[path] ?: return@engine null
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!paused) {
                        paused = true
                        engine.pause("job-1")
                    }
                    return super.read(b, off, len)
                }
            }
        })
        engine.start()
        engine.enqueue(listOf(job()))
        advanceUntilIdle()
        assertEquals(UploadItemState.PAUSED, engine.only().state)
        assertTrue(client.files.isEmpty())

        engine.resume("job-1")
        advanceUntilIdle()
        assertEquals(UploadItemState.COMPLETED, engine.only().state)
        assertTrue(noTemps(client))
    }

    @Test
    fun `cancelling mid-transfer leaves nothing on the server`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        lateinit var engine: UploadEngine
        engine = engine(client, open = { path ->
            val bytes = local[path] ?: return@engine null
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    engine.cancel("job-1")
                    return super.read(b, off, len)
                }
            }
        })
        engine.start()
        engine.enqueue(listOf(job()))
        advanceUntilIdle()
        assertEquals(UploadItemState.CANCELLED, engine.only().state)
        assertTrue(client.files.isEmpty())
    }

    @Test
    fun `a job interrupted by process death restarts its file from zero and clears the stale temp`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val staleTemp = "$platformDir/.argosy-job-1-Game.cue${UploadExecutor.TEMP_EXTENSION}"
        client.files[staleTemp] = ByteArray(3)
        val interrupted = job(state = UploadItemState.UPLOADING).let {
            it.copy(files = it.files.mapIndexed { i, f -> if (i == 0) f.copy(state = UploadItemState.UPLOADING, bytesTransferred = 3) else f })
        }
        val store = MemoryStore(listOf(interrupted))
        val engine = engine(client, store)
        engine.start()
        assertEquals(UploadItemState.PENDING, engine.only().state)
        assertEquals(0L, engine.only().files[0].bytesTransferred)
        advanceUntilIdle()
        assertEquals(UploadItemState.COMPLETED, engine.only().state)
        assertArrayEquals(cue, client.files["$platformDir/Game.cue"])
        assertNull(client.files[staleTemp])
        assertTrue(noTemps(client))
    }

    @Test
    fun `a paused job stays paused across a restart`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val engine = engine(client, MemoryStore(listOf(job(state = UploadItemState.PAUSED))))
        engine.start()
        advanceUntilIdle()
        assertEquals(UploadItemState.PAUSED, engine.only().state)
        assertTrue(client.operations.isEmpty())
    }

    @Test
    fun `manual retry of a failed job keeps completed files`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        var failBin = true
        val engine = engine(client, open = { path ->
            if (path.endsWith(".bin") && failBin) null else local[path]?.let(::ByteArrayInputStream)
        })
        engine.start()
        engine.enqueue(listOf(job()))
        advanceUntilIdle()
        assertEquals(UploadItemState.FAILED, engine.only().state)
        assertEquals(UploadErrorKind.SOURCE_UNREADABLE, engine.only().error?.kind)

        failBin = false
        engine.retry("job-1")
        advanceUntilIdle()
        assertEquals(UploadItemState.COMPLETED, engine.only().state)
        assertEquals(1, client.operations.count { it.startsWith("upload") && it.contains("Game.cue") })
    }

    @Test
    fun `only the configured number of jobs run at once`() = runTest {
        val client = FakeRemoteTransferClient().dir(platformDir)
        lateinit var engine: UploadEngine
        var maxActive = 0
        engine = engine(client, open = { path ->
            maxActive = maxOf(maxActive, engine.jobs.value.count { it.state.isActive })
            local[path]?.let(::ByteArrayInputStream)
        })
        engine.start()
        engine.enqueue(listOf(job("a"), job("b").let { it.copy(files = it.files.map { f -> f.copy(remotePath = f.remotePath.replace("Game", "Other")) }) }))
        advanceUntilIdle()
        assertEquals(1, maxActive)
        assertTrue(engine.jobs.value.all { it.state == UploadItemState.COMPLETED })
    }
}
