package com.nendo.argosy.domain.upload

import com.nendo.argosy.domain.model.upload.UploadError

/**
 * Exponential backoff for retryable transfer failures: the delay doubles from [baseDelayMillis] per
 * attempt up to [maxDelayMillis], and a job stops retrying after [maxAttempts] attempts.
 */
data class RetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val baseDelayMillis: Long = DEFAULT_BASE_DELAY_MILLIS,
    val maxDelayMillis: Long = DEFAULT_MAX_DELAY_MILLIS
) {
    fun shouldRetry(error: UploadError?, attempts: Int): Boolean =
        error != null && error.kind.retryable && attempts < maxAttempts

    fun delayFor(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, MAX_SHIFT)
        return (baseDelayMillis shl exponent).coerceAtMost(maxDelayMillis)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_BASE_DELAY_MILLIS = 5_000L
        const val DEFAULT_MAX_DELAY_MILLIS = 10 * 60_000L
        private const val MAX_SHIFT = 20
    }
}
