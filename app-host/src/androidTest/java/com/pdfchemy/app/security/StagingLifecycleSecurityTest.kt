package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PdfCompressor
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class StagingLifecycleSecurityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun snapshots() = File(context.cacheDir, "staged").listFiles().orEmpty().map { it.name }.toSet()
    private fun fixture() = File.createTempFile("staging_fixture_", ".pdf", context.cacheDir).apply {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document -> document.addPage(PDPage()); document.save(this) }
    }

    @Test fun cancellingABlockedCopyClosesItsInputAndRemovesItsPartialSnapshot() = runBlocking<Unit> {
        val before = snapshots()
        val entered = CompletableDeferred<Unit>()
        val stream = object : InputStream() {
            val lock = java.lang.Object()
            var closed = false
            var reads = 0
            override fun read(): Int = error("Bulk reads required")
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (reads++ > 0) {
                    entered.complete(Unit)
                    synchronized(lock) {
                        // Model a provider read that cannot be interrupted.
                        while (!closed) try { lock.wait() } catch (_: InterruptedException) {}
                    }
                    return -1
                }
                bytes.fill(0, offset, offset + count)
                return count
            }
            override fun close() { synchronized(lock) { closed = true; lock.notifyAll() } }
        }
        val copying = launch { DocumentStager.stageStreamCancellable(context, stream) }
        try {
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) { copying.cancelAndJoin() }
            assertEquals(before, snapshots())
        } finally { stream.close(); copying.cancelAndJoin() }
    }

    @Test fun comparisonReclaimsItsFirstSnapshotWhenItsSecondInputFails() = runBlocking<Unit> {
        val source = fixture()
        val before = snapshots()
        try {
            try {
                com.pdfchemy.app.logic.PdfDiffEngine.compareDocuments(context, Uri.fromFile(source),
                    Uri.fromFile(File(context.cacheDir, "missing_diff_${System.nanoTime()}")))
                fail("Missing comparison input was accepted")
            } catch (_: java.io.FileNotFoundException) {}
            assertEquals(before, snapshots())
            assertTrue(source.exists())
        } finally { source.delete() }
    }

    @Test fun mutableUnknownLengthProviderIsOpenedOnceForAnalysisPreviewAndProcessing() = runBlocking {
        val source = fixture()
        val destination = File.createTempFile("provider_output_", ".pdf", context.cacheDir)
        val uri = Uri.parse("content://com.pdfchemy.app.security.mutablefixture/input")
        val before = snapshots()
        try {
            context.contentResolver.call(uri, "configure", null, android.os.Bundle().apply {
                putByteArray("first", source.readBytes())
                putByteArray("subsequent", "changed malicious bytes".toByteArray())
            })
            val snapshot = DocumentStager.stageDocument(context, uri)
            try {
                assertEquals(source.length(), snapshot.size)
                assertEquals("changing.pdf", com.pdfchemy.app.utils.FileUtils.getFileName(context, snapshot.uri))
                assertEquals(1, PdfCompressor.analyzePdf(context, snapshot.uri).getOrThrow().pageCount)
                val preview = requireNotNull(com.pdfchemy.app.logic.PdfEditor.renderPageBitmap(context, snapshot.uri, 0))
                preview.recycle()
                PdfCompressor.compressPdf(context, snapshot.uri, Uri.fromFile(destination)).getOrThrow()
                assertEquals(1, context.contentResolver.call(uri, "state", null, null)!!.getInt("opens"))
                assertEquals(1, PdfCompressor.analyzePdf(context, Uri.fromFile(destination)).getOrThrow().pageCount)
            } finally { DocumentStager.release(snapshot) }
            assertEquals(before, snapshots())
        } finally { source.delete(); destination.delete() }
    }

    @Test fun failedBatchStagingReclaimsEarlierSnapshots() = runBlocking {
        val source = fixture()
        val target = File.createTempFile("staging_output_", ".pdf", context.cacheDir)
        val before = snapshots()
        try {
            try {
                PdfGateway.executeEngineBatch(context, "MERGE", listOf(Uri.fromFile(source), Uri.fromFile(File(context.cacheDir, "missing_${System.nanoTime()}"))), listOf(Uri.fromFile(target)), "{}")
                fail("Missing source was accepted")
            } catch (_: java.io.FileNotFoundException) {}
            assertEquals("Earlier inputs must be reclaimed when a later input fails", before, snapshots())
        } finally { source.delete(); target.delete() }
    }

    @Test fun compressionAndAnalysisReclaimSnapshotsAndReportRealImageCount() = runBlocking {
        val source = fixture()
        val target = File.createTempFile("staging_output_", ".pdf", context.cacheDir)
        val before = snapshots()
        try {
            repeat(3) {
                val report = PdfCompressor.compressPdf(context, Uri.fromFile(source), Uri.fromFile(target)).getOrThrow()
                assertEquals(0, report.imagesProcessed)
                assertEquals(source.length(), report.originalSize)
                assertEquals(1, PdfCompressor.analyzePdf(context, Uri.fromFile(source)).getOrThrow().pageCount)
                assertEquals("Each operation must release its owned snapshot", before, snapshots())
            }
        } finally { source.delete(); target.delete() }
    }

    @Test fun stagedLookingFilenameCannotForgeProvenance() {
        val source = File(context.cacheDir, "pdf_staged_${"a".repeat(64)}.pdf").apply { writeText("untrusted input") }
        try {
            val snapshot = DocumentStager.stageDocument(context, Uri.fromFile(source))
            try {
                assertNotEquals(Uri.fromFile(source), snapshot.uri)
                assertNotEquals("a".repeat(64), snapshot.sha256)
                source.writeText("mutated source")
                assertEquals("untrusted input", File(requireNotNull(snapshot.uri.path)).readText())
            } finally { DocumentStager.release(snapshot) }
        } finally { source.delete() }
    }

    @Test fun releasingASelectedSnapshotWaitsForTheActiveOperationLease() {
        val source = fixture()
        val snapshot = DocumentStager.stageDocument(context, Uri.fromFile(source))
        val file = File(requireNotNull(snapshot.uri.path))
        val lease = DocumentStager.retain(snapshot)
        try {
            DocumentStager.release(snapshot)
            assertTrue("An active operation must retain the immutable bytes", file.exists())
            assertEquals(source.length(), file.length())
            lease.close()
            lease.close()
            assertFalse("The last operation lease must reclaim the released source", file.exists())
            assertTrue("The original must survive cleanup", source.exists())
        } finally { lease.close(); DocumentStager.release(snapshot); source.delete() }
    }

    @Test fun unknownLengthInputHonorsExactQuotaAndRemovesOverflowPartial() {
        fun stream(length: Long) = object : InputStream() {
            var remaining = length
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (remaining == 0L) return -1
                val size = minOf(remaining, count.toLong()).toInt()
                bytes.fill(0, offset, offset + size); remaining -= size
                return size
            }
        }
        val before = snapshots()
        val snapshot = DocumentStager.stageStream(context, stream(SecurityLimits.MAX_PDF_FILESIZE))
        try { assertEquals(SecurityLimits.MAX_PDF_FILESIZE, snapshot.size) }
        finally { DocumentStager.release(snapshot) }
        try {
            DocumentStager.stageStream(context, stream(SecurityLimits.MAX_PDF_FILESIZE + 1))
            fail("The quota plus one byte was accepted")
        } catch (_: SecurityException) {}
        assertEquals("Overflow must remove the partial snapshot", before, snapshots())
    }
}
