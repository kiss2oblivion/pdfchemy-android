package com.pdfchemy.app.security

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.ui.MainViewModel
import com.pdfchemy.app.utils.DocumentStager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Regression tests for SA-03: Session continuity, LRU reader position bounds, and session lease management.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StagedSessionRetentionTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        DocumentStager.releaseAll()
        viewModel = MainViewModel(application)
    }

    @After
    fun tearDown() {
        DocumentStager.releaseAll()
    }

    private fun samplePdf(marker: String = "test"): ByteArray {
        val content = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
                "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
                "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n" +
                "%$marker\nxref\n0 4\n0000000000 65535 f \n" +
                "trailer\n<< /Root 1 0 R >>\nstartxref\n99\n%%EOF\n"
        return content.toByteArray(Charsets.UTF_8)
    }

    @Test
    fun continuityUriHoldsLeaseAcrossReaderExit_andReleasesWhenCleared() {
        val input = ByteArrayInputStream(samplePdf("continuity_doc"))
        val snapshot = DocumentStager.stageStream(application, input, "pdf")
        val uri = snapshot.uri
        val path = uri.path!!

        // 1. Reader holds session lease
        val readerLease = DocumentStager.retain(snapshot)
        assertEquals(1, DocumentStager.activeLeases(snapshot))

        // 2. Reader navigates to a tool (e.g. CompressPdf) via continuity
        viewModel.setContinuityUri(uri)
        assertEquals("Both Reader and continuity should hold leases", 2, DocumentStager.activeLeases(snapshot))

        // 3. Reader disposes: closes its lease and requests release
        readerLease.close()
        DocumentStager.release(snapshot)

        // Document MUST survive because continuity holds a lease!
        assertEquals(1, DocumentStager.activeLeases(snapshot))
        assertTrue("Document file must stay on disk during tool continuity", File(path).exists())
        assertNotNull(DocumentStager.stagedFor(uri))

        // 4. Tool finishes or clears continuity
        viewModel.clearContinuityUri()
        assertEquals(0, DocumentStager.activeLeases(snapshot))
        assertFalse("Document file must be removed after continuity clears", File(path).exists())
        assertNull(DocumentStager.stagedFor(uri))
    }

    @Test
    fun readerPositionsMap_isStrictlyBoundedTo32Entries() {
        // Populate 50 distinct positions
        for (i in 1..50) {
            viewModel.readerPositions["doc_$i"] = MainViewModel.ReaderPosition(page = i, offset = i * 10)
        }

        // Strict bound of 32 entries
        assertEquals("readerPositions must not exceed 32 entries", 32, viewModel.readerPositions.size)

        // Oldest 18 entries (doc_1 .. doc_18) must have been evicted
        for (i in 1..18) {
            assertFalse("Eldest entry doc_$i should be evicted", viewModel.readerPositions.containsKey("doc_$i"))
        }

        // Recent entries (doc_19 .. doc_50) must remain
        for (i in 19..50) {
            assertTrue("Recent entry doc_$i must be present", viewModel.readerPositions.containsKey("doc_$i"))
        }
    }
}
