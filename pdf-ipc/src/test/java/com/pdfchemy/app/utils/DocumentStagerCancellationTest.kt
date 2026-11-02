package com.pdfchemy.app.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentStagerCancellationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val sample = "%PDF-1.4\n% cancellation probe\n%%EOF\n".toByteArray()

    @Before fun setUp() = DocumentStager.releaseAll()
    @After fun tearDown() = DocumentStager.releaseAll()

    /** Force close/read to fail with IOException even if runInterruptible interrupts first. */
    private inner class CloseFailingInput : InputStream() {
        val reading = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        private var first = true
        override fun read(): Int = error("Bulk read required")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (first) {
                first = false
                sample.copyInto(buffer, offset)
                return sample.size
            }
            reading.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closed.count != 0L) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Cancellation failed to close the blocked input" }
                try { closed.await(remaining, TimeUnit.NANOSECONDS) }
                catch (_: InterruptedException) { /* Wait for close so IOException wins deterministically. */ }
            }
            throw IOException("Read unblocked by cancellation closing its input")
        }
        override fun close() { closed.countDown() }
    }

    @Test fun cancelledUnleasedReadDoesNotFailItsParent() = verifyCancellation(leased = false)
    @Test fun cancelledLeasedReadDoesNotFailItsParent() = verifyCancellation(leased = true)

    private fun verifyCancellation(leased: Boolean) = runBlocking<Unit> {
        val input = CloseFailingInput()
        val owner = ResourceTransferOwner<DocumentStager.StagedLease> { it.discard(null) }
        val staging = launch(Dispatchers.IO) {
            if (leased) DocumentStager.stageStreamCancellationSafe(context, input, owner)
            else DocumentStager.stageStreamCancellable(context, input)
        }
        try {
            assertTrue("Read did not reach the close-driven failure barrier", input.reading.await(5, TimeUnit.SECONDS))
            assertEquals(1, DocumentStager.activeInFlightSlots)
            assertEquals(sample.size.toLong(), DocumentStager.activeInFlightBytes)
            assertTrue("Partial snapshot must exist before cancellation", snapshots().isNotEmpty())
            staging.cancelAndJoin()
            assertTrue(staging.isCancelled)
            assertTrue("Expected cancellation must leave the caller active", currentCoroutineContext().isActive)
            assertFalse("No resource may escape through the transfer owner", owner.hasPending)
            assertClean()
        } finally {
            input.close()
            withContext(NonCancellable) { staging.cancelAndJoin() }
            owner.close()
        }
    }

    @Test fun activeReadFailureRemainsIOExceptionAndReclaimsPartialSnapshot() = runBlocking<Unit> {
        for (leased in listOf(false, true)) {
            val problem = IOException("Genuine active input failure")
            val input = object : InputStream() {
                private var first = true
                override fun read(): Int = error("Bulk read required")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!first) throw problem
                    first = false
                    sample.copyInto(buffer, offset)
                    return sample.size
                }
            }
            val failure = runCatching {
                if (leased) DocumentStager.stageStreamCancellableWithLease(context, input)
                else DocumentStager.stageStreamCancellable(context, input)
            }.exceptionOrNull()
            assertTrue("Active I/O failures must not become cancellation", failure is IOException)
            assertEquals(problem.message, failure?.message)
            assertTrue(currentCoroutineContext().isActive)
            assertClean()
        }
    }

    private fun snapshots() = File(context.cacheDir, "staged").listFiles().orEmpty().filter { it.name.startsWith("snapshot_") }
    private fun assertClean() {
        assertEquals(0, DocumentStager.activeInFlightSlots)
        assertEquals(0L, DocumentStager.activeInFlightBytes)
        assertEquals(0, DocumentStager.stagedCount)
        assertTrue("Partial snapshot must be deleted", snapshots().isEmpty())
    }
}
