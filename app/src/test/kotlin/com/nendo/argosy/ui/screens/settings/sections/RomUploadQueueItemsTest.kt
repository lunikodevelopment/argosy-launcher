package com.nendo.argosy.ui.screens.settings.sections

import com.nendo.argosy.domain.model.upload.UploadItemState
import com.nendo.argosy.domain.upload.TransferHold
import com.nendo.argosy.ui.screens.settings.RomUploadJobAction
import com.nendo.argosy.ui.screens.settings.RomUploadJobUi
import com.nendo.argosy.ui.screens.settings.RomUploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomUploadQueueItemsTest {

    private fun job(id: String, state: UploadItemState, finishedAt: Long? = null) = RomUploadJobUi(
        id = id,
        title = "Game $id",
        serverName = "nas",
        state = state,
        bytesDone = 0,
        bytesTotal = 100,
        errorKind = null,
        finishedAtMillis = finishedAt
    )

    @Test
    fun `an empty queue shows only the empty note`() {
        val items = buildRomUploadQueueItems(RomUploadState())

        assertTrue(items.contains(RomUploadQueueItem.Empty))
        assertEquals(0, romUploadQueueMaxFocusIndex(RomUploadState()))
        assertEquals(null, romUploadQueueItemAtFocusIndex(0, RomUploadState()))
    }

    @Test
    fun `open jobs come first and finished jobs newest first`() {
        val state = RomUploadState(
            jobs = listOf(
                job("old", UploadItemState.COMPLETED, finishedAt = 1),
                job("running", UploadItemState.UPLOADING),
                job("new", UploadItemState.FAILED, finishedAt = 5)
            )
        )

        val focusable = (0..romUploadQueueMaxFocusIndex(state)).map { romUploadQueueItemAtFocusIndex(it, state) }

        assertEquals(
            listOf(
                RomUploadQueueItem.Job("running", finished = false),
                RomUploadQueueItem.Job("new", finished = true),
                RomUploadQueueItem.Job("old", finished = true),
                RomUploadQueueItem.PauseAll,
                RomUploadQueueItem.ClearFinished
            ),
            focusable
        )
    }

    @Test
    fun `resume all appears only with a paused job`() {
        val paused = RomUploadState(jobs = listOf(job("a", UploadItemState.PAUSED)))

        val items = buildRomUploadQueueItems(paused)

        assertTrue(items.contains(RomUploadQueueItem.ResumeAll))
        assertFalse(items.contains(RomUploadQueueItem.PauseAll))
    }

    @Test
    fun `a hold notice shows only while work is waiting`() {
        val held = RomUploadState(hold = TransferHold.METERED_NETWORK)
        val heldWithWork = held.copy(jobs = listOf(job("a", UploadItemState.WAITING_FOR_NETWORK)))

        assertFalse(buildRomUploadQueueItems(held).any { it is RomUploadQueueItem.Notice })
        assertTrue(buildRomUploadQueueItems(heldWithWork).any { it is RomUploadQueueItem.Notice })
    }

    @Test
    fun `each state offers only the actions the engine honours`() {
        assertEquals(
            listOf(RomUploadJobAction.PAUSE, RomUploadJobAction.CANCEL),
            job("a", UploadItemState.UPLOADING).actions
        )
        assertEquals(
            listOf(RomUploadJobAction.RESUME, RomUploadJobAction.CANCEL),
            job("a", UploadItemState.PAUSED).actions
        )
        assertEquals(listOf(RomUploadJobAction.RETRY), job("a", UploadItemState.FAILED).actions)
        assertEquals(listOf(RomUploadJobAction.RETRY), job("a", UploadItemState.CANCELLED).actions)
        assertTrue(job("a", UploadItemState.COMPLETED).actions.isEmpty())
        assertTrue(job("a", UploadItemState.SKIPPED).actions.isEmpty())
    }
}
