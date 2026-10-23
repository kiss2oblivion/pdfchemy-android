package com.pdfchemy.app.security

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.MainActivity
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
import org.robolectric.Robolectric
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
    fun refreshingSameContinuityUriCannotDeletePendingSnapshot() {
        val snapshot = DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf()), "pdf")
        viewModel.setContinuityUri(snapshot.uri)
        DocumentStager.release(snapshot)
        assertTrue(DocumentStager.isPendingRelease(snapshot.uri))

        viewModel.setContinuityUri(snapshot.uri)

        assertTrue("Refreshing the handoff must not unlink its document", File(snapshot.uri.path!!).exists())
        assertEquals(1, DocumentStager.activeLeases(snapshot))
        viewModel.clearContinuityUri()
        assertFalse(File(snapshot.uri.path!!).exists())
    }

    @Test
    fun returningReaderConsumesOnlyAfterAcquiringItsOwnLease() {
        val snapshot = DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf()), "pdf")
        val uri = snapshot.uri
        viewModel.setContinuityUri(uri)
        DocumentStager.release(snapshot)
        val returningReader = DocumentStager.stageDocumentWithLease(application, uri)

        viewModel.clearContinuityUri(uri)

        assertNull(viewModel.continuityDocumentUri.value)
        assertEquals(1, DocumentStager.activeLeases(snapshot))
        assertTrue(File(uri.path!!).exists())
        returningReader.close()
        assertFalse(File(uri.path!!).exists())
    }

    @Test
    fun staleReaderCannotClearAnotherDocumentsHandoff() {
        val first = DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf("first")), "pdf")
        val second = DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf("second")), "pdf")
        viewModel.setContinuityUri(first.uri)
        viewModel.setContinuityUri(second.uri)

        viewModel.clearContinuityUri(first.uri)

        assertEquals(second.uri, viewModel.continuityDocumentUri.value)
        assertEquals(1, DocumentStager.activeLeases(second))
        assertTrue(File(second.uri.path!!).exists())
        viewModel.clearContinuityUri(second.uri)
        assertFalse(File(second.uri.path!!).exists())
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

    private fun cleanupOrphanedCacheFiles(activity: MainActivity, context: Context) {
        MainActivity::class.java.getDeclaredMethod("cleanupOrphanedCacheFiles", Context::class.java)
            .apply { isAccessible = true }.invoke(activity, context)
    }

    @Test
    fun startupSweepPreservesActivelyLeasedSnapshotEvenWhenAged() {
        val bytes = samplePdf("leased_snapshot_test")
        val leased = ByteArrayInputStream(bytes).use {
            DocumentStager.stageStreamWithLease(application, it, "pdf")
        }
        val file = File(leased.document.uri.path!!)
        val oneHourAndTenMinutesAgo = System.currentTimeMillis() - 4_200_000L
        try {
            assertTrue(file.setLastModified(oneHourAndTenMinutesAgo))
            assertEquals(1, DocumentStager.activeLeases(leased.document))
            assertTrue(DocumentStager.isLeased(file))
            assertTrue(file.exists())

            val activity = Robolectric.buildActivity(MainActivity::class.java).get()
            cleanupOrphanedCacheFiles(activity, application)

            // Verification under SC-11: File must survive, exact bytes intact, lease still active
            assertTrue("Actively leased staged snapshot must survive startup cache cleanup", file.exists())
            assertEquals("Exact snapshot bytes must be preserved", bytes.size.toLong(), file.length())
            assertEquals("Staging lease count must remain intact", 1, DocumentStager.activeLeases(leased.document))
            assertNotNull("Document must remain tracked in DocumentStager", DocumentStager.stagedFor(leased.document.uri))
        } finally {
            leased.close()
            DocumentStager.release(leased.document)
        }
    }

    @Test
    fun startupSweepReclaimsEligibleUnleasedAgedSnapshot() {
        val bytes = samplePdf("unleased_aged_snapshot_test")
        val stagedLease = ByteArrayInputStream(bytes).use {
            DocumentStager.stageStreamWithLease(application, it, "pdf")
        }
        val doc = stagedLease.document
        val file = File(doc.uri.path!!)
        stagedLease.close() // release lease, active count becomes 0
        assertEquals(0, DocumentStager.activeLeases(doc))
        assertFalse(DocumentStager.isLeased(file))

        val oneHourAndTenMinutesAgo = System.currentTimeMillis() - 4_200_000L
        file.setLastModified(oneHourAndTenMinutesAgo)

        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        cleanupOrphanedCacheFiles(activity, application)

        // Verification under SC-11: Unleased, aged snapshot should be cleanly reclaimed
        assertFalse("Eligible aged unleased snapshot should be deleted from disk", file.exists())
        assertNull("Reclaimed snapshot should be removed from DocumentStager tracking", DocumentStager.stagedFor(doc.uri))
    }

    @Test
    fun startupSweepPreservesRecentUnleasedSnapshot() {
        val bytes = samplePdf("unleased_recent_snapshot_test")
        val stagedLease = ByteArrayInputStream(bytes).use {
            DocumentStager.stageStreamWithLease(application, it, "pdf")
        }
        val doc = stagedLease.document
        val file = File(doc.uri.path!!)
        stagedLease.close() // unleased
        assertEquals(0, DocumentStager.activeLeases(doc))

        // Fresh file (modified just now)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        cleanupOrphanedCacheFiles(activity, application)

        // Recent unleased file must NOT be deleted yet (within 1 hour window)
        assertTrue("Recent unleased snapshot must not be deleted", file.exists())
        assertNotNull("Recent snapshot should still be tracked in DocumentStager", DocumentStager.stagedFor(doc.uri))
        DocumentStager.release(doc)
    }
}
