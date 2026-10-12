package com.nendo.argosy.data.upload

import com.nendo.argosy.domain.model.upload.DuplicatePolicy
import com.nendo.argosy.domain.model.upload.GameLayout
import com.nendo.argosy.domain.model.upload.TransferProtocol
import com.nendo.argosy.domain.model.upload.UploadError
import com.nendo.argosy.domain.model.upload.UploadErrorKind
import com.nendo.argosy.domain.model.upload.UploadFileTask
import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.model.upload.UploadJob
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadQueueFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val moshi = Moshi.Builder().build()

    private fun job() = UploadJob(
        id = "j1",
        profileId = "p1",
        protocol = TransferProtocol.SMB,
        gameTitle = "Crash",
        platformSlug = "psx",
        remoteFolderName = "psx",
        remoteGameDirectory = "library/roms/psx",
        libraryRoot = "library",
        platformDirectory = "library/roms/psx",
        layout = GameLayout.SINGLE_FILE,
        files = listOf(
            UploadFileTask(
                "j1-0", "/sd/Crash.chd", "Crash.chd", "library/roms/psx/Crash.chd", 42,
                state = UploadItemState.FAILED, attempts = 2, error = UploadError(UploadErrorKind.NO_SPACE, "full")
            )
        ),
        duplicatePolicy = DuplicatePolicy.KEEP_BOTH,
        overwriteConfirmed = false,
        allowCreatePlatformFolder = true,
        createdAtMillis = 10,
        state = UploadItemState.FAILED,
        error = UploadError(UploadErrorKind.NO_SPACE, "full"),
        attempts = 2,
        completedAtMillis = 20
    )

    @Test
    fun `a saved queue loads back identically except for transient byte counts`() {
        val store = UploadQueueFileStore(File(temp.root, "q/uploads.json"), moshi)
        store.save(listOf(job()))
        assertEquals(listOf(job()), store.load())
        assertFalse(File(temp.root, "q/uploads.json.tmp").exists())
    }

    @Test
    fun `a missing or corrupt document loads as an empty queue`() {
        val file = File(temp.root, "uploads.json")
        val store = UploadQueueFileStore(file, moshi)
        assertTrue(store.load().isEmpty())
        file.writeText("{not json")
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `a job with an unknown protocol is dropped without losing the others`() {
        val file = File(temp.root, "uploads.json")
        val store = UploadQueueFileStore(file, moshi)
        store.save(listOf(job(), job().copy(id = "j2")))
        file.writeText(file.readText().replaceFirst("\"SMB\"", "\"FTP\""))
        assertEquals(listOf("j2"), store.load().map { it.id })
    }
}
