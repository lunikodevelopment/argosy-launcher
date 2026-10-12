package com.nendo.argosy.domain.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BandwidthLimiterTest {

    private class FakeTime {
        var nanos = 0L
        val slept = mutableListOf<Long>()
        fun sleep(millis: Long) {
            slept += millis
            nanos += millis * 1_000_000
        }
    }

    @Test
    fun `an unlimited rate never sleeps`() {
        val time = FakeTime()
        val limiter = BandwidthLimiter({ 0L }, { time.nanos }, time::sleep)
        limiter.wrap(ByteArrayInputStream(ByteArray(1_000_000))).readBytes()
        assertTrue(time.slept.isEmpty())
    }

    @Test
    fun `reading at a limit takes roughly size over rate`() {
        val time = FakeTime()
        val limiter = BandwidthLimiter({ 100_000L }, { time.nanos }, time::sleep)
        val data = limiter.wrap(ByteArrayInputStream(ByteArray(500_000))).readBytes()
        assertEquals(500_000, data.size)
        val seconds = time.nanos / 1_000_000_000.0
        assertTrue("took $seconds s", seconds in 4.5..5.5)
    }

    @Test
    fun `retry backoff doubles and caps`() {
        val policy = RetryPolicy(maxAttempts = 4, baseDelayMillis = 1_000, maxDelayMillis = 5_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L), (1..4).map(policy::delayFor))
    }
}
