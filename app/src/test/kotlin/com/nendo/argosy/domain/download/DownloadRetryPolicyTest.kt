package com.nendo.argosy.domain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryPolicyTest {

    private val policy = DownloadRetryPolicy(maxAttempts = 3, baseDelayMillis = 1_000, maxDelayMillis = 10_000)

    @Test
    fun `delay doubles per attempt and stops at the cap`() {
        assertEquals(1_000, policy.delayFor(1))
        assertEquals(2_000, policy.delayFor(2))
        assertEquals(4_000, policy.delayFor(3))
        assertEquals(8_000, policy.delayFor(4))
        assertEquals(10_000, policy.delayFor(5))
        assertEquals(10_000, policy.delayFor(500))
    }

    @Test
    fun `attempts without progress accumulate and give up after the limit`() {
        var attempt = policy.nextAttempt(0, madeProgress = true)
        assertEquals(1, attempt)
        attempt = policy.nextAttempt(attempt, madeProgress = false)
        attempt = policy.nextAttempt(attempt, madeProgress = false)
        assertEquals(3, attempt)
        assertTrue(policy.shouldRetry(attempt))
        attempt = policy.nextAttempt(attempt, madeProgress = false)
        assertFalse(policy.shouldRetry(attempt))
    }

    @Test
    fun `progress resets the count so a slow advancing link is never abandoned`() {
        val attempt = policy.nextAttempt(previousAttempts = 3, madeProgress = true)

        assertEquals(1, attempt)
        assertTrue(policy.shouldRetry(attempt))
    }

    @Test
    fun `network errors and server hiccups are transient while client errors are not`() {
        assertTrue(DownloadRetryPolicy.isTransientHttp(null))
        assertTrue(DownloadRetryPolicy.isTransientHttp(503))
        assertTrue(DownloadRetryPolicy.isTransientHttp(429))
        assertTrue(DownloadRetryPolicy.isTransientHttp(408))
        assertFalse(DownloadRetryPolicy.isTransientHttp(401))
        assertFalse(DownloadRetryPolicy.isTransientHttp(403))
        assertFalse(DownloadRetryPolicy.isTransientHttp(404))
    }
}
