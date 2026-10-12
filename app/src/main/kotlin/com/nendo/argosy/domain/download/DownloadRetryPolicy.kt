package com.nendo.argosy.domain.download

/**
 * Backoff for ROM downloads that stopped on a transient condition. A failure that moved no new
 * bytes counts against [maxAttempts]; one that made progress starts the count over, so a flaky link
 * that keeps advancing is never abandoned. The partial file stays on disk for a Range resume.
 */
class DownloadRetryPolicy(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    private val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS
) {
    fun nextAttempt(previousAttempts: Int, madeProgress: Boolean): Int =
        if (madeProgress) 1 else previousAttempts + 1

    fun shouldRetry(attempt: Int): Boolean = attempt <= maxAttempts

    fun delayFor(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, MAX_SHIFT)
        return (baseDelayMillis shl exponent).coerceAtMost(maxDelayMillis)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 6
        const val DEFAULT_BASE_DELAY_MILLIS = 5_000L
        const val DEFAULT_MAX_DELAY_MILLIS = 5 * 60_000L
        private const val MAX_SHIFT = 20

        fun isTransientHttp(code: Int?): Boolean =
            code == null || code == 408 || code == 425 || code == 429 || code in 500..599
    }
}
