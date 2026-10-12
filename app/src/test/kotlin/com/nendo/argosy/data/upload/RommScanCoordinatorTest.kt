package com.nendo.argosy.data.upload

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RommScanCoordinatorTest {

    private class FakeEndpoint(
        private val startResult: ScanHttpResult,
        private val statuses: List<String?>
    ) : ScanEndpoint {
        var requested: List<String> = emptyList()
        var polls = 0

        override suspend fun start(platformFolders: List<String>): ScanHttpResult {
            requested = platformFolders
            return startResult
        }

        override suspend fun status(taskId: String): ScanHttpResult {
            val status = statuses[polls.coerceAtMost(statuses.lastIndex)]
            polls++
            return ScanHttpResult(200, taskId, status, if (status == "finished") 3 else null)
        }
    }

    private val coordinator = RommScanCoordinator(pollIntervalMillis = 10, timeoutMillis = 100)

    @Test
    fun `an accepted scan is followed until it finishes`() = runTest {
        val endpoint = FakeEndpoint(ScanHttpResult(202, "t1", "queued", null), listOf("queued", "started", "finished"))
        assertEquals(ScanOutcome.Completed(3), coordinator.scan(endpoint, listOf("gba", "gba", "psx")))
        assertEquals(listOf("gba", "psx"), endpoint.requested)
        assertEquals(3, endpoint.polls)
    }

    @Test
    fun `server answers map to distinct outcomes`() = runTest {
        val cases = mapOf(
            401 to ScanOutcome.Unauthorized,
            403 to ScanOutcome.MissingPermission,
            404 to ScanOutcome.Unsupported(null),
            409 to ScanOutcome.AlreadyRunning,
            503 to ScanOutcome.NoWorker
        )
        cases.forEach { (code, expected) ->
            assertEquals(expected, coordinator.scan(FakeEndpoint(ScanHttpResult(code, null, null, null), listOf(null)), listOf("gba")))
        }
    }

    @Test
    fun `a failed task and a task that never ends are reported`() = runTest {
        assertEquals(
            ScanOutcome.Failed("failed"),
            coordinator.scan(FakeEndpoint(ScanHttpResult(202, "t", null, null), listOf("failed")), listOf("gba"))
        )
        assertEquals(
            ScanOutcome.TimedOut,
            coordinator.scan(FakeEndpoint(ScanHttpResult(202, "t", null, null), listOf("started")), listOf("gba"))
        )
    }
}
