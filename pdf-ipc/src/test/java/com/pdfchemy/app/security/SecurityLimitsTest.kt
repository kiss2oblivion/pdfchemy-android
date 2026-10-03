package com.pdfchemy.app.security

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SecurityLimitsTest {
    private val hash = "a".repeat(64)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Untrusted request was accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun batchRequiresExactMetadataForEveryDescriptor() {
        rejected { SecurityLimits.requireBatch(2, 1, null, longArrayOf(1, 1)) }
        rejected { SecurityLimits.requireBatch(2, 1, arrayOf(hash, hash), null) }
        rejected { SecurityLimits.requireBatch(2, 1, arrayOf(hash), longArrayOf(1, 1)) }
        rejected { SecurityLimits.requireBatch(2, 1, arrayOf(hash, hash), longArrayOf(1)) }
        rejected { SecurityLimits.requireBatch(1, 1, arrayOf(hash, hash), longArrayOf(1)) }
        rejected { SecurityLimits.requireBatch(1, 1, arrayOf(hash), longArrayOf(1, 1)) }
        rejected { SecurityLimits.requireBatch(2, 1, arrayOf(hash, "z".repeat(64)), longArrayOf(1, 1)) }
        rejected { SecurityLimits.requireBatch(2, 1, arrayOf(hash, hash), longArrayOf(1, 0)) }
        rejected { SecurityLimits.requireBatch(33, 1, Array(33) { hash }, LongArray(33) { 1 }) }
        rejected { SecurityLimits.requireBatch(1, 33, arrayOf(hash), longArrayOf(1)) }
        SecurityLimits.requireBatch(2, 1, arrayOf(hash, hash.uppercase()), longArrayOf(1, 1))
    }
    @Test fun hugeMediaBoxesDownscaleAndInvalidDimensionsFail() {
        listOf(1e9 to 1e9, Int.MAX_VALUE.toDouble() to 1.0, 1.0 to Int.MAX_VALUE.toDouble()).forEach { (w, h) ->
            val (width, height) = SecurityLimits.safeRenderSize(w, h, 2.5)
            SecurityLimits.requirePixels(width, height)
        }
        rejected { SecurityLimits.safeRenderSize(Double.NaN, 1.0) }
        rejected { SecurityLimits.safeRenderSize(Double.POSITIVE_INFINITY, 1.0) }
        rejected { SecurityLimits.safeRenderSize(0.0, 1.0) }
        rejected { SecurityLimits.requirePixels(8192, 8192) }
    }
    @Test fun outputQuotaRejectsBeforeWritingTheExcessByte() {
        val bytes = ByteArrayOutputStream()
        val bounded = BoundedOutputStream(bytes, 5)
        bounded.write(byteArrayOf(1, 2, 3, 4, 5))
        try { bounded.write(6); fail("Overflow accepted") } catch (_: SecurityException) {}
        assertEquals(5, bytes.size())
    }
    @Test fun gateHasNoBacklogAndIndependentWatchdogFires() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            val lease = gate.acquire(100)!!
            repeat(12) { assertNull(gate.acquire(100)) }
            assertTrue(terminated.await(2, TimeUnit.SECONDS))
            lease.close()
            assertNull(gate.acquire(100)) // Deadline means the process must terminate, not resume work.
        }
    }
    @Test fun completedOperationDisarmsItsWatchdog() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            gate.acquire(100)!!.close()
            assertNotNull(gate.acquire(10_000).also { it!!.close() })
            assertFalse(terminated.await(200, TimeUnit.MILLISECONDS))
        }
    }
}
