package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadFileTask
import com.nendo.argosy.domain.model.upload.UploadItemState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UploadExecutorTest {

    private val root = "/lib"
    private val platformDir = "/lib/roms/gba"
    private val content = "pokemon-emerald-rom-bytes".toByteArray()
    private val local = mapOf("/sd/ROMs/gba/Emerald.gba" to content)

    private fun task(remote: String = "$platformDir/Emerald.gba", size: Long = content.size.toLong()) =
        UploadFileTask("t1", "/sd/ROMs/gba/Emerald.gba", "Emerald.gba", remote, size)

    private fun context(
        policy: DuplicatePolicy = DuplicatePolicy.SKIP,
        overwriteConfirmed: Boolean = false,
        allowCreate: Boolean = false
    ) = ExecutionContext(root, TransferProtocol.SFTP, policy, overwriteConfirmed, allowCreate, platformDir, "job123456789abc")

    private fun executor(client: FakeRemoteTransferClient, states: MutableList<UploadItemState> = mutableListOf()) =
        UploadExecutor(client, { path -> local[path]?.let(::ByteArrayInputStream) }, { states += it })

    private fun noTempFiles(client: FakeRemoteTransferClient) =
        client.files.keys.none { it.endsWith(UploadExecutor.TEMP_EXTENSION) }

    @Test
    fun `a new file is uploaded to a temporary name then renamed after the size check`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val states = mutableListOf<UploadItemState>()
        val outcome = executor(client, states).execute(task(), context(), {}, CancellationCheck.NEVER)
        assertEquals(UploadItemState.COMPLETED, outcome.state)
        assertArrayEquals(content, client.files["$platformDir/Emerald.gba"])
        val upload = client.operations.first { it.startsWith("upload") }
        assertTrue(upload.endsWith(UploadExecutor.TEMP_EXTENSION))
        assertTrue(client.operations.last().startsWith("rename"))
        assertTrue(noTempFiles(client))
        assertEquals(
            listOf(
                UploadItemState.CHECKING_DESTINATION, UploadItemState.UPLOADING,
                UploadItemState.VERIFYING, UploadItemState.FINALIZING
            ),
            states
        )
    }

    @Test
    fun `an interrupted upload never appears under the final name`() {
        val client = FakeRemoteTransferClient().dir(platformDir).apply { failUploadAfterBytes = 8 }
        val outcome = executor(client).execute(task(), context(), {}, CancellationCheck.NEVER)
        assertEquals(UploadItemState.FAILED, outcome.state)
        assertEquals(UploadErrorKind.NETWORK_LOST, outcome.error?.error?.kind)
        assertNull(client.files["$platformDir/Emerald.gba"])
        assertTrue(noTempFiles(client))
    }

    @Test
    fun `a size mismatch fails verification and keeps the final name free`() {
        val client = FakeRemoteTransferClient().dir(platformDir).apply { truncateUploadsBy = 3 }
        val outcome = executor(client).execute(task(), context(), {}, CancellationCheck.NEVER)
        assertEquals(UploadErrorKind.SIZE_MISMATCH, outcome.error?.error?.kind)
        assertNull(client.files["$platformDir/Emerald.gba"])
        assertTrue(noTempFiles(client))
    }

    @Test
    fun `cancellation mid transfer removes the temporary file`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        var calls = 0
        val outcome = executor(client).execute(task(), context(), {}, { ++calls > 3 })
        assertEquals(UploadItemState.CANCELLED, outcome.state)
        assertNull(client.files["$platformDir/Emerald.gba"])
        assertTrue(noTempFiles(client))
    }

    @Test
    fun `an existing rom is skipped by default and never touched`() {
        val existing = "original".toByteArray()
        val client = FakeRemoteTransferClient().dir(platformDir)
        client.files["$platformDir/Emerald.gba"] = existing
        val outcome = executor(client).execute(task(), context(), {}, CancellationCheck.NEVER)
        assertEquals(UploadItemState.SKIPPED, outcome.state)
        assertArrayEquals(existing, client.files["$platformDir/Emerald.gba"])
        assertFalse(client.operations.any { it.startsWith("upload") || it.startsWith("delete") })
    }

    @Test
    fun `overwrite without confirmation is refused`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        client.files["$platformDir/Emerald.gba"] = "original".toByteArray()
        val outcome = executor(client).execute(task(), context(DuplicatePolicy.OVERWRITE), {}, CancellationCheck.NEVER)
        assertEquals(UploadErrorKind.REMOTE_EXISTS, outcome.error?.error?.kind)
        assertArrayEquals("original".toByteArray(), client.files["$platformDir/Emerald.gba"])
    }

    @Test
    fun `confirmed overwrite replaces only after the new copy is verified`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        client.files["$platformDir/Emerald.gba"] = "original".toByteArray()
        val outcome = executor(client).execute(
            task(), context(DuplicatePolicy.OVERWRITE, overwriteConfirmed = true), {}, CancellationCheck.NEVER
        )
        assertEquals(UploadItemState.COMPLETED, outcome.state)
        assertArrayEquals(content, client.files["$platformDir/Emerald.gba"])
        val deleteIndex = client.operations.indexOf("delete $platformDir/Emerald.gba")
        val uploadIndex = client.operations.indexOfFirst { it.startsWith("upload") }
        assertTrue(uploadIndex in 0 until deleteIndex)
    }

    @Test
    fun `keep both picks the next free numbered name`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        client.files["$platformDir/Emerald.gba"] = "a".toByteArray()
        client.files["$platformDir/Emerald (2).gba"] = "b".toByteArray()
        val outcome = executor(client).execute(task(), context(DuplicatePolicy.KEEP_BOTH), {}, CancellationCheck.NEVER)
        assertEquals("$platformDir/Emerald (3).gba", outcome.finalPath)
        assertArrayEquals(content, client.files["$platformDir/Emerald (3).gba"])
        assertArrayEquals("a".toByteArray(), client.files["$platformDir/Emerald.gba"])
    }

    @Test
    fun `cancel policy aborts on an existing file`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        client.files["$platformDir/Emerald.gba"] = "a".toByteArray()
        val outcome = executor(client).execute(task(), context(DuplicatePolicy.CANCEL), {}, CancellationCheck.NEVER)
        assertEquals(UploadErrorKind.REMOTE_EXISTS, outcome.error?.error?.kind)
    }

    @Test
    fun `a missing platform folder is not created without confirmation`() {
        val client = FakeRemoteTransferClient().dir(root)
        val outcome = executor(client).execute(task(), context(), {}, CancellationCheck.NEVER)
        assertEquals(UploadErrorKind.PLATFORM_FOLDER_MISSING, outcome.error?.error?.kind)
        assertTrue(client.operations.none { it.startsWith("mkdirs") })
    }

    @Test
    fun `a confirmed platform folder is created`() {
        val client = FakeRemoteTransferClient().dir(root)
        val outcome = executor(client).execute(task(), context(allowCreate = true), {}, CancellationCheck.NEVER)
        assertEquals(UploadItemState.COMPLETED, outcome.state)
        assertTrue(client.operations.contains("mkdirs $platformDir"))
    }

    @Test
    fun `game subfolders inside an existing platform folder are created`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val outcome = executor(client).execute(
            task("$platformDir/Game/Disc 1.gba"), context(), {}, CancellationCheck.NEVER
        )
        assertEquals(UploadItemState.COMPLETED, outcome.state)
        assertTrue(client.operations.contains("mkdirs $platformDir/Game"))
    }

    @Test
    fun `a destination outside the root is rejected before any remote call`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val outcome = executor(client).execute(task("/etc/cron.d/evil"), context(), {}, CancellationCheck.NEVER)
        assertEquals(UploadErrorKind.DESTINATION_INVALID, outcome.error?.error?.kind)
        assertTrue(client.operations.isEmpty())
    }

    @Test
    fun `a stale temporary file from an earlier attempt is replaced not appended`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        executor(client).execute(task(), context(), {}, CancellationCheck.NEVER)
        client.files.remove("$platformDir/Emerald.gba")
        val tempName = client.operations.first { it.startsWith("upload") }.removePrefix("upload ")
        client.files[tempName] = "stale-partial".toByteArray()
        val outcome = executor(client).execute(task(), context(), {}, CancellationCheck.NEVER)
        assertEquals(UploadItemState.COMPLETED, outcome.state)
        assertArrayEquals(content, client.files["$platformDir/Emerald.gba"])
    }

    @Test
    fun `a source that changed size fails instead of uploading a mismatched file`() {
        val client = FakeRemoteTransferClient().dir(platformDir)
        val outcome = executor(client).execute(task(size = 999), context(), {}, CancellationCheck.NEVER)
        assertEquals(UploadItemState.FAILED, outcome.state)
        assertNull(client.files["$platformDir/Emerald.gba"])
    }
}
