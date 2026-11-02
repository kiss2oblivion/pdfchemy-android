package com.pdfchemy.app.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.security.SecurityLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Live copies must keep their quota reservations when a finishing activity releases snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentStagerShutdownTest {
    @Test
    fun shutdownMustNotEraseReservationsOfStillRunningCopies() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pool = Executors.newSingleThreadExecutor()
        val firstChunkAccounted = CountDownLatch(1)
        val continueCopy = CountDownLatch(1)
        val bytes = "%PDF-1.4\n%bounded reservation probe\n%%EOF\n".toByteArray()
        val stream = object : InputStream() {
            var reads = 0
            override fun read(): Int = error("Bulk read required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (reads++ == 0) {
                    bytes.copyInto(buffer, offset)
                    return bytes.size
                }
                firstChunkAccounted.countDown()
                check(continueCopy.await(10, TimeUnit.SECONDS)) { "Probe barrier timeout" }
                return -1
            }
        }
        DocumentStager.releaseAll()
        val future = pool.submit<DocumentStager.StagedLease> {
            DocumentStager.stageStreamWithLease(context, stream, "pdf")
        }
        var result: DocumentStager.StagedLease? = null
        try {
            assertTrue("Copy did not reach bounded stall", firstChunkAccounted.await(10, TimeUnit.SECONDS))
            assertEquals(1, DocumentStager.activeInFlightSlots)
            assertEquals(bytes.size.toLong(), DocumentStager.activeInFlightBytes)
            DocumentStager.releaseAll()
            assertEquals("Shutdown erased a live copy's slot reservation", 1, DocumentStager.activeInFlightSlots)
            assertEquals("Shutdown erased a live copy's byte reservation", bytes.size.toLong(), DocumentStager.activeInFlightBytes)
        } finally {
            continueCopy.countDown()
            try { result = future.get(10, TimeUnit.SECONDS) } finally {
                result?.lease?.close()
                DocumentStager.releaseAll()
                pool.shutdownNow()
                assertTrue("Probe worker failed to terminate", pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun shutdownMustNotPermitMoreThan64LiveOrReservedDocuments() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pool = Executors.newSingleThreadExecutor()
        val stalled = CountDownLatch(1)
        val continueCopy = CountDownLatch(1)
        val bytes = "%PDF-1.4\n%bounded quota probe\n%%EOF\n".toByteArray()
        val heldLeases = mutableListOf<Closeable>()
        val stream = object : InputStream() {
            var delivered = false
            override fun read(): Int = error("Bulk read required")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!delivered) {
                    delivered = true
                    bytes.copyInto(buffer, offset)
                    return bytes.size
                }
                stalled.countDown()
                check(continueCopy.await(20, TimeUnit.SECONDS)) { "Probe barrier timeout" }
                return -1
            }
        }
        DocumentStager.releaseAll()
        val future = pool.submit<DocumentStager.StagedLease> {
            DocumentStager.stageStreamWithLease(context, stream, "pdf")
        }
        try {
            assertTrue("Copy did not reach bounded stall", stalled.await(10, TimeUnit.SECONDS))
            DocumentStager.releaseAll()
            var admitted = 0
            repeat(SecurityLimits.MAX_STAGED_DOCUMENTS) {
                try {
                    val staged = DocumentStager.stageStreamWithLease(context, ByteArrayInputStream(bytes), "pdf")
                    heldLeases += staged.lease
                    admitted++
                } catch (_: IllegalArgumentException) {
                    // Admission may reject once live copies and published leases reach the cap.
                }
            }
            continueCopy.countDown()
            heldLeases += future.get(10, TimeUnit.SECONDS).lease
            assertTrue(
                "Shutdown allowed ${DocumentStager.stagedCount} leased snapshots after admitting $admitted new copies; cap is ${SecurityLimits.MAX_STAGED_DOCUMENTS}",
                DocumentStager.stagedCount <= SecurityLimits.MAX_STAGED_DOCUMENTS
            )
        } finally {
            continueCopy.countDown()
            try {
                if (!future.isDone) heldLeases += future.get(10, TimeUnit.SECONDS).lease
            } finally {
                heldLeases.forEach { it.close() }
                DocumentStager.releaseAll()
                pool.shutdownNow()
                assertTrue("Probe worker failed to terminate", pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }
}
