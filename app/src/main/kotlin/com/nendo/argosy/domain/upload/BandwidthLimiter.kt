package com.nendo.argosy.domain.upload

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Token bucket shared by every running transfer. [bytesPerSecond] is read on each acquisition, so a
 * limit change applies mid-transfer; zero or less means unlimited.
 */
class BandwidthLimiter(
    private val bytesPerSecond: () -> Long,
    private val clock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    private var available = 0L
    private var lastRefillNanos = clock()

    fun acquire(bytes: Int) {
        var remaining = bytes.toLong()
        while (remaining > 0) {
            val rate = bytesPerSecond()
            if (rate <= 0) return
            val waitMillis = synchronized(this) {
                refill(rate)
                val granted = minOf(available, remaining)
                available -= granted
                remaining -= granted
                if (remaining > 0) ((remaining.coerceAtMost(rate) * MILLIS_PER_SECOND) / rate).coerceAtLeast(1) else 0
            }
            if (waitMillis > 0) sleeper(waitMillis)
        }
    }

    private fun refill(rate: Long) {
        val now = clock()
        val elapsed = now - lastRefillNanos
        if (elapsed <= 0) return
        available = (available + elapsed * rate / NANOS_PER_SECOND).coerceAtMost(rate)
        lastRefillNanos = now
    }

    fun wrap(stream: InputStream): InputStream = object : FilterInputStream(stream) {
        override fun read(): Int {
            acquire(1)
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val chunk = minOf(len, CHUNK_BYTES)
            acquire(chunk)
            return super.read(b, off, chunk)
        }
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val CHUNK_BYTES = 64 * 1024
    }
}
