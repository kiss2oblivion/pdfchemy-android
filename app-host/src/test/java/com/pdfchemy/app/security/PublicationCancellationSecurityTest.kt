package com.pdfchemy.app.security

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.logic.DestinationProvenance
import com.pdfchemy.app.logic.OutputDestination
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.PublicationState
import com.pdfchemy.app.logic.audio.AudioOutputPublisher
import com.pdfchemy.app.ui.MainViewModel
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression and security test suite for SC-13:
 * Bounded host publication resources, cooperative stream cancellation,
 * writer-stop safety before deletion, and platform limitation documentation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PublicationCancellationSecurityTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        PublicationCoordinator.resetForTesting()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        PublicationCoordinator.resetForTesting()
        unmockkAll()
    }

    /**
     * Stalling output stream that tracks bytes, entry latch, close signal,
     * and throws IOException on close to unblock pending writes.
     */
    private class StallingOutputStream(
        private val enterLatch: CountDownLatch,
        private val blockLatch: CountDownLatch
    ) : OutputStream() {
        val closed = AtomicBoolean(false)
        val writtenBytes = AtomicInteger(0)

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get()) throw IOException("Stream is closed")
            enterLatch.countDown()
            try {
                // Wait on blockLatch or throw when closed
                while (!closed.get()) {
                    if (blockLatch.await(20, TimeUnit.MILLISECONDS)) break
                }
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Write interrupted", ie)
            }
            if (closed.get()) throw IOException("Stream closed asynchronously during write")
            writtenBytes.addAndGet(len)
        }

        override fun close() {
            closed.set(true)
            blockLatch.countDown()
        }
    }

    @Test
    fun sc13_01_coroutineCancellation_closesActiveStreamAndUnblocksWriterPromptly() = runBlocking {
        val tempWav = File.createTempFile("sc13_audio_", ".wav", application.cacheDir).apply {
            writeBytes(ByteArray(1024))
        }
        val entered = CountDownLatch(1)
        val block = CountDownLatch(1)
        val stream = StallingOutputStream(entered, block)
        val cleanupCount = AtomicInteger(0)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = scope.launch {
            AudioOutputPublisher.copy(
                tempWav,
                { stream },
                { cleanupCount.incrementAndGet() }
            )
        }

        try {
            assertTrue("Writer must enter write loop", entered.await(5, TimeUnit.SECONDS))
            assertFalse("Stream must be open while writing", stream.closed.get())

            // Cancel writer coroutine
            writer.cancel()

            // Coroutine cancellation must immediately close the active stream via PublicationHandle
            val terminated = withTimeoutOrNull(2000) {
                writer.join()
                true
            } ?: false

            assertTrue("Writer must terminate promptly upon stream closure", terminated)
            assertTrue("Stream must have been closed asynchronously", stream.closed.get())
            assertEquals("Cleanup must be executed on cancellation/abort", 1, cleanupCount.get())
            assertEquals("Publication coordinator must release handle upon completion", 0, PublicationCoordinator.activeCount())
        } finally {
            block.countDown()
            scope.cancel()
            tempWav.delete()
        }
    }

    @Test
    fun sc13_02_uninterruptibleStalledWriter_preventsPrematureDeletionAndEnforcesPlatformLimits() = runBlocking {
        val viewModel = MainViewModel(application)
        val destUri = Uri.parse("content://com.pdfchemy.fixture/uninterruptible_dest.pdf")

        // Mock DocumentFile to observe deletion calls
        mockkStatic(DocumentFile::class)
        val mockDoc = mockk<DocumentFile>()
        val deleteAttempted = AtomicBoolean(false)
        every { DocumentFile.fromSingleUri(any(), destUri) } returns mockDoc
        every { mockDoc.delete() } answers {
            deleteAttempted.set(true)
            true
        }

        OutputPolicy.registerFreshCopy(destUri)
        viewModel.addPendingOutputUri(destUri, isFreshCopy = true)

        // Lower stopTimeoutMs for rapid test evaluation of writer-stop boundary
        PublicationCoordinator.stopTimeoutMs = 100L

        val writerEntered = CountDownLatch(1)
        val writerRelease = CountDownLatch(1)

        // Manually acquire a handle simulating an active stalled publication writer on destUri
        val handle = PublicationCoordinator.acquire(listOf(destUri), 1024L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stalledJob = scope.launch {
            writerEntered.countDown()
            // Simulates an uninterruptible Binder call or uncooperative kernel D-state
            try {
                writerRelease.await(10, TimeUnit.SECONDS)
            } finally {
                handle.close()
            }
        }
        handle.attachJob(stalledJob)

        assertTrue(writerEntered.await(5, TimeUnit.SECONDS))
        assertTrue("Destination must be reported as having an active writer", PublicationCoordinator.hasActiveWriter(destUri))

        // Cancel the operation in MainViewModel
        viewModel.cancelOperation(application)

        // Wait beyond the 100ms stopTimeoutMs grace period
        delay(250)

        // Documented platform limit: if writer cannot be stopped, file must NOT be deleted
        assertFalse("Destination must NOT be deleted while writer thread is still active (prevents TOCTOU corruption)", deleteAttempted.get())
        assertTrue("Fresh copy status must remain quarantined", OutputPolicy.isFreshCopy(destUri))
        assertTrue("Publication coordinator must retain reservation until writer actually terminates", PublicationCoordinator.hasActiveWriter(destUri))

        // Now release the stall so writer terminates cleanly
        writerRelease.countDown()
        withTimeout(2000) { stalledJob.join() }

        assertFalse("Once writer terminates, destination is no longer marked active", PublicationCoordinator.hasActiveWriter(destUri))
        assertEquals("Active publication count must return to 0", 0, PublicationCoordinator.activeCount())
        scope.cancel()
    }

    @Test
    fun sc13_03_repeatedCancellationAndRetry_enforcesPublicationConcurrencyAndByteBudgets() = runBlocking {
        // Configure small budget to verify backpressure against volume exhaustion
        PublicationCoordinator.maxConcurrentPublications = 2
        PublicationCoordinator.maxOutstandingBytes = 1000L

        val uri1 = Uri.parse("content://fixture/doc1.pdf")
        val uri2 = Uri.parse("content://fixture/doc2.pdf")
        val uri3 = Uri.parse("content://fixture/doc3.pdf")

        // Acquire first publication (400 bytes)
        val handle1 = PublicationCoordinator.acquire(listOf(uri1), 400L)
        assertEquals(1, PublicationCoordinator.activeCount())
        assertEquals(400L, PublicationCoordinator.activeBytes())

        // Acquire second publication (400 bytes)
        val handle2 = PublicationCoordinator.acquire(listOf(uri2), 400L)
        assertEquals(2, PublicationCoordinator.activeCount())
        assertEquals(800L, PublicationCoordinator.activeBytes())

        // Concurrency limit reached (max = 2): third concurrent attempt must be rejected
        val concurrencyExceeded = runCatching {
            PublicationCoordinator.acquire(listOf(uri3), 100L)
        }
        assertTrue("Attempt exceeding maxConcurrentPublications must fail", concurrencyExceeded.isFailure)
        assertTrue(concurrencyExceeded.exceptionOrNull() is SecurityException)
        assertTrue(concurrencyExceeded.exceptionOrNull()?.message.orEmpty().contains("concurrency limit exceeded", ignoreCase = true))

        // Close handle 2: frees 1 concurrency slot and 400 bytes (now 1 active, 400 bytes)
        handle2.close()
        assertEquals(1, PublicationCoordinator.activeCount())
        assertEquals(400L, PublicationCoordinator.activeBytes())

        // Byte quota check: attempting to acquire 700 bytes (400 + 700 = 1100 > 1000) must fail
        val quotaExceeded = runCatching {
            PublicationCoordinator.acquire(listOf(uri3), 700L)
        }
        assertTrue("Attempt exceeding maxOutstandingBytes must fail", quotaExceeded.isFailure)
        assertTrue(quotaExceeded.exceptionOrNull() is SecurityException)
        assertTrue(quotaExceeded.exceptionOrNull()?.message.orEmpty().contains("quota exceeded", ignoreCase = true))

        // Acquiring within budget (500 bytes: 400 + 500 = 900 <= 1000) succeeds
        val handle3 = PublicationCoordinator.acquire(listOf(uri3), 500L)
        assertEquals(2, PublicationCoordinator.activeCount())
        assertEquals(900L, PublicationCoordinator.activeBytes())

        // Clean up remaining handles
        handle1.close()
        handle3.close()
        assertEquals(0, PublicationCoordinator.activeCount())
        assertEquals(0L, PublicationCoordinator.activeBytes())
    }

    @Test
    fun sc13_04_destinationCollision_rejectsConcurrentPublicationToSameUri() = runBlocking {
        val sharedUri = Uri.parse("content://fixture/collision_target.pdf")
        val otherUri = Uri.parse("content://fixture/other_target.pdf")

        val handle1 = PublicationCoordinator.acquire(listOf(sharedUri), 500L)
        assertTrue(PublicationCoordinator.hasActiveWriter(sharedUri))

        // A second publication targeting the same URI must be rejected to prevent write race corruption
        val collisionResult = runCatching {
            PublicationCoordinator.acquire(listOf(sharedUri), 500L)
        }
        assertTrue("Concurrent publication to identical URI must fail", collisionResult.isFailure)
        assertTrue(collisionResult.exceptionOrNull() is IllegalStateException)
        assertTrue(collisionResult.exceptionOrNull()?.message.orEmpty().contains("currently in use", ignoreCase = true))

        // Publication targeting an unrelated URI succeeds
        val handle2 = PublicationCoordinator.acquire(listOf(otherUri), 500L)
        assertEquals(2, PublicationCoordinator.activeCount())

        handle1.close()
        assertFalse(PublicationCoordinator.hasActiveWriter(sharedUri))

        // Once handle 1 closes, sharedUri can be acquired safely
        val handle3 = PublicationCoordinator.acquire(listOf(sharedUri), 500L)
        assertEquals(2, PublicationCoordinator.activeCount())

        handle2.close()
        handle3.close()
        assertEquals(0, PublicationCoordinator.activeCount())
    }

    @Test
    fun sc13_05_audioOutputPublisher_deletePartial_refusesToDeleteWhenWriterIsActive() = runBlocking {
        val destUri = Uri.parse("content://fixture/active_audio_dest.wav")

        mockkStatic(DocumentFile::class)
        val mockDoc = mockk<DocumentFile>()
        val deleteCalled = AtomicBoolean(false)
        every { DocumentFile.fromSingleUri(any(), destUri) } returns mockDoc
        every { mockDoc.delete() } answers { deleteCalled.set(true); true }

        OutputPolicy.registerFreshCopy(destUri)
        assertTrue(OutputPolicy.isFreshCopy(destUri))

        // Acquire handle on destUri
        val handle = PublicationCoordinator.acquire(listOf(destUri), 256L)
        assertTrue(PublicationCoordinator.hasActiveWriter(destUri))

        // Call deletePartial while writer is active
        AudioOutputPublisher.deletePartial(application, destUri, isFreshCopy = true)

        assertFalse("deletePartial must NOT delete destination while writer is active", deleteCalled.get())
        assertTrue("Destination must remain fresh copy while quarantined", OutputPolicy.isFreshCopy(destUri))

        // Release writer handle
        handle.close()
        assertFalse(PublicationCoordinator.hasActiveWriter(destUri))

        // Call deletePartial again now that writer is inactive
        AudioOutputPublisher.deletePartial(application, destUri, isFreshCopy = true)

        assertTrue("deletePartial must delete destination once writer has stopped", deleteCalled.get())
        assertFalse("Fresh copy status must be unregistered after deletion", OutputPolicy.isFreshCopy(destUri))
    }
}
