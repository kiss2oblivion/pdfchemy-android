package com.pdfchemy.app.security

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.sandbox.NativeRendererCoordinator
import com.pdfchemy.app.utils.DocumentStager
import com.pdfchemy.app.utils.ResourceTransferOwner
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Regression tests for SC-06: Cancellation-safe lease and bitmap transfer across dispatcher boundaries.
 * Verifies that when coroutines are cancelled after resource allocation but before or during caller
 * dispatcher resumption, staging leases are not leaked, snapshots do not suffer lease drift,
 * bitmaps are recycled, and concurrent borrowers remain completely intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StagingTransferSecurityTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

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

    private fun createPdfFile(name: String, marker: String = "test"): File {
        val file = File(application.cacheDir, "$name.pdf")
        file.writeBytes(samplePdf(marker))
        return file
    }

    /**
     * Dispatcher that allows intercepting the resumption dispatch runnable to simulate
     * cancellation occurring after background completion but before caller resumption.
     */
    private class ControllableDispatcher(
        private val delegate: CoroutineDispatcher = Dispatchers.Default
    ) : CoroutineDispatcher() {
        private val dispatchCount = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile
        var onDispatch: ((Runnable) -> Unit)? = null
        @Volatile
        var onResumption: ((Job?, Runnable) -> Unit)? = null

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val count = dispatchCount.incrementAndGet()
            val resumptionHandler = onResumption
            if (count > 1 && resumptionHandler != null) {
                resumptionHandler(context[Job], block)
                return
            }
            val handler = onDispatch
            if (handler != null) {
                handler(block)
            } else {
                delegate.dispatch(context, block)
            }
        }
    }

    @Test
    fun sc06_01_cancellationBeforeCallerResumption_leavesNoLeaseOrAbandonedSnapshot() = runBlocking {
        val testFile = createPdfFile("cancel_before_resume", "sc06_probe")
        val sourceUri = Uri.fromFile(testFile)

        val callerDispatcher = ControllableDispatcher(Dispatchers.IO)
        var cancelledCaught = false
        var stagedDocPath: String? = null

        val transferOwner = ResourceTransferOwner<DocumentStager.StagedLease> { uncommitted ->
            stagedDocPath = uncommitted.document.uri.path
            uncommitted.discard(sourceUri)
        }

        // Intercept resumption deterministically before launch
        callerDispatcher.onResumption = { job, runnable ->
            job?.cancel()
            runnable.run()
        }

        // Launch on our controlled dispatcher
        val job = launch(callerDispatcher) {
            try {
                DocumentStager.stageDocumentCancellationSafe(
                    context = application,
                    sourceUri = sourceUri,
                    transferOwner = transferOwner
                )
            } catch (e: CancellationException) {
                cancelledCaught = true
                throw e
            }
        }

        job.join()

        assertTrue("Job must have been cancelled", job.isCancelled)
        assertEquals("DocumentStager active leases must return to 0 after cancellation",
            0, DocumentStager.activeLeases(sourceUri))

        // Ensure the snapshot was unleased and removed, leaving zero quota drift
        val path = stagedDocPath
        if (path != null) {
            assertEquals("Snapshot leases must be 0", 0, DocumentStager.activeLeases(Uri.parse("file://$path")))
            assertFalse("Snapshot file must not remain on disk after discarded transfer", File(path).exists())
        }
    }

    @Test
    fun sc06_02_stagingTransferOwner_normalCommitHandoff_andDisposalCleanup() = runBlocking {
        val testFile = createPdfFile("transfer_handoff", "handoff_marker")
        val sourceUri = Uri.fromFile(testFile)

        var activeLease: java.io.Closeable? = null
        val transferOwner = ResourceTransferOwner<DocumentStager.StagedLease> { uncommitted ->
            uncommitted.discard(sourceUri)
        }

        try {
            val stagedLease = DocumentStager.stageDocumentCancellationSafe(
                context = application,
                sourceUri = sourceUri,
                transferOwner = transferOwner
            )
            // Transfer committed successfully to caller
            activeLease = stagedLease.lease
            assertEquals("Committed lease must be active", 1, DocumentStager.activeLeases(stagedLease.document))
            assertFalse("Transfer owner should have no pending resource after commit", transferOwner.hasPending)
        } finally {
            activeLease?.close()
            transferOwner.close()
        }

        assertEquals("Active leases must return to 0 when closed", 0, DocumentStager.activeLeases(sourceUri))
    }

    @Test
    fun sc06_02b_stagingTransferOwner_disposalBeforeStagingReturns_discardsResource() = runBlocking {
        val testFile = createPdfFile("disposal_during_stage", "disposal_marker")
        val sourceUri = Uri.fromFile(testFile)

        var discardCallbackInvoked = false
        val transferOwner = ResourceTransferOwner<DocumentStager.StagedLease> { uncommitted ->
            discardCallbackInvoked = true
            uncommitted.discard(sourceUri)
        }

        // Screen is disposed while staging is queued or executing
        transferOwner.close()
        assertTrue("Transfer owner must be closed", transferOwner.isClosed)

        // Attempting to stage into a closed transfer owner must immediately discard
        val staged = DocumentStager.stageDocumentWithLease(application, sourceUri)
        transferOwner.stage(staged)

        assertTrue("Discard callback must be invoked when staging into closed owner", discardCallbackInvoked)
        assertNull("Commit must return null on closed owner", transferOwner.commit())
        assertEquals("Active leases must be 0 after immediate discard", 0, DocumentStager.activeLeases(sourceUri))
    }

    @Test
    fun sc06_03_bitmapTransfer_recyclesBitmapWhenCancelledDuringResumption() = runBlocking {
        var recycledBitmap: Bitmap? = null
        val bitmapOwner = ResourceTransferOwner<Bitmap> { uncommitted ->
            recycledBitmap = uncommitted
            if (!uncommitted.isRecycled) {
                uncommitted.recycle()
            }
        }

        val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertFalse("Bitmap initially not recycled", testBitmap.isRecycled)

        // Simulate staging bitmap across dispatcher
        bitmapOwner.stage(testBitmap)
        assertTrue("Owner has pending bitmap", bitmapOwner.hasPending)

        // Cancellation occurs before commit
        bitmapOwner.discard()

        assertTrue("Bitmap must be recycled on discard", testBitmap.isRecycled)
        assertSame("Discarded bitmap must be the allocated instance", testBitmap, recycledBitmap)
        assertNull("Commit after discard must return null", bitmapOwner.commit())
    }

    @Test
    fun sc06_03b_bitmapTransfer_normalCommitPreservesBitmapUntilExplicitRecycle() {
        val bitmapOwner = ResourceTransferOwner<Bitmap> { uncommitted ->
            if (!uncommitted.isRecycled) uncommitted.recycle()
        }

        val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmapOwner.stage(testBitmap)

        val committed = bitmapOwner.commit()
        assertNotNull("Committed bitmap must not be null", committed)
        assertSame(testBitmap, committed)
        assertFalse("Committed bitmap must not be recycled yet", committed!!.isRecycled)

        // Discard or close on owner does nothing to already committed bitmap
        bitmapOwner.close()
        assertFalse("Committed bitmap remains intact after owner close", committed.isRecycled)

        // Caller recycles when done
        committed.recycle()
        assertTrue("Bitmap recycled by caller", committed.isRecycled)
    }

    @Test
    fun sc06_04_repeatCancellationDoesNotCauseQuotaDrift_preservesActiveBorrowers() = runBlocking {
        // 1. Establish an active borrower holding a legitimate lease on document B
        val borrowerFile = createPdfFile("active_borrower_doc", "borrower_session")
        val borrowerUri = Uri.fromFile(borrowerFile)
        val borrowerStagedLease = DocumentStager.stageDocumentWithLease(application, borrowerUri)
        val borrowerDoc = borrowerStagedLease.document

        assertEquals("Active borrower must have 1 lease", 1, DocumentStager.activeLeases(borrowerDoc))
        assertTrue("Borrower document file must exist", File(borrowerDoc.uri.path!!).exists())

        val initialStagedCount = DocumentStager.stagedCount
        val initialBytes = DocumentStager.totalStagedBytes

        // 2. Perform 10 repeated cancellations of new documents and the borrower document
        for (i in 1..10) {
            val ephemeralFile = createPdfFile("ephemeral_$i", "noise_$i")
            val ephemeralUri = Uri.fromFile(ephemeralFile)

            val transferOwner = ResourceTransferOwner<DocumentStager.StagedLease> { uncommitted ->
                uncommitted.discard(ephemeralUri)
            }

            val controlledDispatcher = ControllableDispatcher(Dispatchers.IO)
            controlledDispatcher.onResumption = { job, runnable ->
                job?.cancel()
                runnable.run()
            }

            val job = launch(controlledDispatcher) {
                DocumentStager.stageDocumentCancellationSafe(
                    context = application,
                    sourceUri = ephemeralUri,
                    transferOwner = transferOwner
                )
            }

            job.join()
            assertTrue("Job $i must have been cancelled", job.isCancelled)

            // Verify: No lease leak on ephemeral file
            assertEquals("No lease should leak on ephemeral document $i",
                0, DocumentStager.activeLeases(ephemeralUri))

            // Verify: Active borrower document lease must remain intact throughout!
            assertEquals("Borrower document lease must remain 1 throughout loop iteration $i",
                1, DocumentStager.activeLeases(borrowerDoc))
            assertTrue("Borrower file must exist at iteration $i",
                File(borrowerDoc.uri.path!!).exists())
        }

        // 3. Quota check: Staged count and bytes must return to baseline (no quota drift)
        assertEquals("Staged count must not have drifted", initialStagedCount, DocumentStager.stagedCount)
        assertEquals("Total staged bytes must not have drifted", initialBytes, DocumentStager.totalStagedBytes)

        // 4. Verify a normal staging operation still succeeds cleanly
        val regularFile = createPdfFile("regular_after_cancellations", "success_marker")
        val regularUri = Uri.fromFile(regularFile)
        val successfulLease = DocumentStager.stageDocumentCancellationSafe(application, regularUri)
        assertEquals(1, DocumentStager.activeLeases(successfulLease.document))
        successfulLease.close()
        DocumentStager.release(successfulLease.document)

        // 5. Cleanup active borrower
        borrowerStagedLease.close()
        DocumentStager.release(borrowerDoc)
        assertEquals("Borrower document lease should return to 0", 0, DocumentStager.activeLeases(borrowerDoc))
    }
}
