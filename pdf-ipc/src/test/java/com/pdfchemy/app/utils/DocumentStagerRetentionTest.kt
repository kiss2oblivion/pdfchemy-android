package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.security.SecurityLimits
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Regression tests for SA-03: Staged document capability retention, LRU pruning, and bounded session cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentStagerRetentionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        DocumentStager.releaseAll()
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
    fun repeatedOpensDoNotExhaustQuota_returnsToBoundedBaseline() {
        // Stage 70 distinct documents sequentially without manual release (exceeding MAX_STAGED_DOCUMENTS = 64)
        for (i in 1..70) {
            val input = ByteArrayInputStream(samplePdf("doc_$i"))
            val snapshot = DocumentStager.stageStream(context, input, "pdf")
            assertNotNull("Snapshot should be created", snapshot)
            // Staged count must never exceed SecurityLimits.MAX_STAGED_DOCUMENTS
            assertTrue(
                "Staged count ${DocumentStager.stagedCount} must remain <= ${SecurityLimits.MAX_STAGED_DOCUMENTS}",
                DocumentStager.stagedCount <= SecurityLimits.MAX_STAGED_DOCUMENTS
            )
        }

        // Unleased snapshots must be trimmed to the documented live-session bound (MAX_UNLEASED_DOCUMENTS = 4)
        assertTrue(
            "Final occupancy ${DocumentStager.stagedCount} must return to bounded baseline <= 5",
            DocumentStager.stagedCount <= DocumentStager.MAX_UNLEASED_DOCUMENTS + 1
        )
    }

    @Test
    fun liveWorkerLeaseProtectsDocumentFromEviction() {
        // Stage target document and acquire a worker lease
        val targetInput = ByteArrayInputStream(samplePdf("critical_target"))
        val targetSnapshot = DocumentStager.stageStream(context, targetInput, "pdf")
        val targetPath = targetSnapshot.uri.path!!

        val lease = DocumentStager.retain(targetSnapshot)
        assertEquals(1, DocumentStager.activeLeases(targetSnapshot))

        // Now flood DocumentStager with 60 other documents to force eviction
        for (i in 1..60) {
            val input = ByteArrayInputStream(samplePdf("noise_$i"))
            DocumentStager.stageStream(context, input, "pdf")
        }

        // Target document MUST survive because activeLeases > 0
        assertEquals("Target document must still have active lease", 1, DocumentStager.activeLeases(targetSnapshot))
        assertNotNull("Target document must still exist in staged registry", DocumentStager.stagedFor(targetSnapshot.uri))
        assertTrue("Target file on disk must exist", File(targetPath).exists())

        // UI requests release while worker is still running
        DocumentStager.release(targetSnapshot)
        assertTrue("Document must be marked pendingRelease while worker lease is active",
            DocumentStager.isPendingRelease(targetSnapshot.uri))
        assertTrue("File must remain on disk while worker lease is held", File(targetPath).exists())

        // Worker finishes and closes lease
        lease.close()
        assertEquals(0, DocumentStager.activeLeases(targetSnapshot))
        assertFalse("File must be deleted after final lease closes", File(targetPath).exists())
        assertNull("Snapshot must be removed from staged registry", DocumentStager.stagedFor(targetSnapshot.uri))
    }

    @Test
    fun retainByUri_incrementsAndDecrementsCorrectly() {
        val input = ByteArrayInputStream(samplePdf("uri_retain"))
        val snapshot = DocumentStager.stageStream(context, input, "pdf")
        val uri = snapshot.uri

        assertEquals(0, DocumentStager.activeLeases(uri))

        val lease1 = DocumentStager.retain(uri)
        assertNotNull(lease1)
        assertEquals(1, DocumentStager.activeLeases(uri))

        val lease2 = DocumentStager.retain(uri)
        assertNotNull(lease2)
        assertEquals(2, DocumentStager.activeLeases(uri))

        lease1!!.close()
        assertEquals(1, DocumentStager.activeLeases(uri))

        lease2!!.close()
        assertEquals(0, DocumentStager.activeLeases(uri))
    }

    @Test
    fun releaseIsIdempotent_doesNotThrowOrCorruptState() {
        val input = ByteArrayInputStream(samplePdf("idempotent"))
        val snapshot = DocumentStager.stageStream(context, input, "pdf")

        // First release deletes the file
        DocumentStager.release(snapshot)
        assertNull(DocumentStager.stagedFor(snapshot.uri))

        // Subsequent releases on the same snapshot are safe no-ops
        DocumentStager.release(snapshot)
        DocumentStager.release(snapshot.uri)
        assertEquals(0, DocumentStager.activeLeases(snapshot.uri))
    }

    @Test
    fun explicitReleaseDeletesImmediatelyWhenUnleased() {
        val input = ByteArrayInputStream(samplePdf("immediate_delete"))
        val snapshot = DocumentStager.stageStream(context, input, "pdf")
        val path = snapshot.uri.path!!

        assertTrue(File(path).exists())
        assertEquals(0, DocumentStager.activeLeases(snapshot))

        DocumentStager.release(snapshot)
        assertFalse("File must be deleted immediately when release is called with 0 leases", File(path).exists())
        assertNull(DocumentStager.stagedFor(snapshot.uri))
    }

    @Test
    fun atomicStageAndRetain_survivesConcurrentFloodingWithoutEviction() {
        // Stage target document atomically with lease
        val targetInput = ByteArrayInputStream(samplePdf("atomic_target"))
        val (targetSnapshot, targetLease) = DocumentStager.stageStreamWithLease(context, targetInput, "pdf")
        val targetPath = targetSnapshot.uri.path!!

        assertEquals("Atomically staged document must immediately have active lease = 1",
            1, DocumentStager.activeLeases(targetSnapshot))

        // Flood with 20 unleased documents, each triggering trimUnleasedToBound()
        for (i in 1..20) {
            val input = ByteArrayInputStream(samplePdf("flood_$i"))
            DocumentStager.stageStream(context, input, "pdf")
        }

        // Target document must survive because activeLeases > 0 protects it from trimUnleasedToBound
        assertEquals("Target document must still have active lease", 1, DocumentStager.activeLeases(targetSnapshot))
        assertNotNull("Target document must still exist in staged registry", DocumentStager.stagedFor(targetSnapshot.uri))
        assertTrue("Target file on disk must exist", File(targetPath).exists())

        // Cleanup
        targetLease.close()
        assertEquals(0, DocumentStager.activeLeases(targetSnapshot))
        DocumentStager.release(targetSnapshot)
        assertFalse("File must be deleted after release with 0 leases", File(targetPath).exists())
        assertNull("Snapshot must be removed from staged registry", DocumentStager.stagedFor(targetSnapshot.uri))
    }

    @Test
    fun concurrencyRegression_unleasedSnapshotIsEvicted_atomicallyLeasedSnapshotSurvives() {
        // 1. Stage unleased document A
        val docA = DocumentStager.stageStream(context, ByteArrayInputStream(samplePdf("unleased_a")), "pdf")
        assertEquals(0, DocumentStager.activeLeases(docA))

        // 2. Stage atomically leased document B
        val docBLease = DocumentStager.stageStreamWithLease(context, ByteArrayInputStream(samplePdf("leased_b")), "pdf")
        assertEquals(1, DocumentStager.activeLeases(docBLease.document))

        // 3. Flood with 10 unleased documents to trigger LRU eviction (exceeding MAX_UNLEASED_DOCUMENTS = 4)
        for (i in 1..10) {
            DocumentStager.stageStream(context, ByteArrayInputStream(samplePdf("noise_$i")), "pdf")
        }

        // Document A (unleased) must be evicted by trimUnleasedToBound
        assertNull("Unleased Document A must have been evicted", DocumentStager.stagedFor(docA.uri))

        // Document B (atomically leased) must survive!
        assertNotNull("Atomically leased Document B must survive eviction", DocumentStager.stagedFor(docBLease.document.uri))
        assertEquals(1, DocumentStager.activeLeases(docBLease.document))
        assertTrue("File B must exist on disk", File(docBLease.document.uri.path!!).exists())

        // Cleanup doc B
        docBLease.close()
        DocumentStager.release(docBLease.document)
        assertNull(DocumentStager.stagedFor(docBLease.document.uri))
    }

    @Test
    fun concurrentCoroutinesStagingWithLease_noExpiredStagedCapability() = runBlocking {
        // Launch 16 parallel coroutines staging distinct inputs simultaneously
        val jobs = (0 until 16).map { i ->
            async(Dispatchers.IO) {
                val input = ByteArrayInputStream(samplePdf("coroutine_stress_$i"))
                DocumentStager.stageStreamCancellableWithLease(context, input)
            }
        }

        val stagedLeases = jobs.awaitAll()
        assertEquals("All 16 concurrent staging operations must succeed", 16, stagedLeases.size)

        stagedLeases.forEach { (doc, lease) ->
            assertEquals("Each capability must have active lease = 1", 1, DocumentStager.activeLeases(doc))
            assertNotNull("Capability must exist in staged registry", DocumentStager.stagedFor(doc.uri))
            lease.close()
            DocumentStager.release(doc)
            assertNull("Cleaned up capability must be removed", DocumentStager.stagedFor(doc.uri))
        }
    }
}
