package com.pdfchemy.app.security

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for SC-07: Staging synchronization, non-blocking Main-thread lease operations,
 * and quota reservation management during stalled ContentProvider queries, opens, and reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StagingSynchronizationSecurityTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    companion object {
        private fun samplePdf(marker: String = "test"): ByteArray {
            val content = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
                    "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
                    "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n" +
                    "%$marker\nxref\n0 4\n0000000000 65535 f \n" +
                    "trailer\n<< /Root 1 0 R >>\nstartxref\n99\n%%EOF\n"
            return content.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Bounded deterministic test ContentProvider that can stall in query or openFile.
     */
    class StallingContentProvider(private val backingFile: File) : ContentProvider() {
        companion object {
            const val AUTHORITY = "com.pdfchemy.test.stalling"

            var queryLatch: CountDownLatch? = null
            var openLatch: CountDownLatch? = null
            var onQueryEntered: (() -> Unit)? = null
            var onOpenEntered: (() -> Unit)? = null

            fun reset() {
                queryLatch = null
                openLatch = null
                onQueryEntered = null
                onOpenEntered = null
            }
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            onQueryEntered?.invoke()
            queryLatch?.await(10, TimeUnit.SECONDS)
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            cursor.addRow(arrayOf<Any>("stalled_test.pdf", backingFile.length()))
            return cursor
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            onOpenEntered?.invoke()
            openLatch?.await(10, TimeUnit.SECONDS)
            return ParcelFileDescriptor.open(backingFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    /**
     * Deterministic InputStream that can yield initial bytes and then stall during reading
     * until released or closed upon cancellation.
     */
    class StallingInputStream(
        private val sample: ByteArray,
        private val stallLatch: CountDownLatch,
        private val onReadStalled: CountDownLatch
    ) : InputStream() {
        private var pos = 0
        private var hasStalled = false
        @Volatile private var isClosed = false

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (isClosed) throw java.io.IOException("Stream closed")
            if (pos > 0 && !hasStalled) {
                hasStalled = true
                onReadStalled.countDown()
                stallLatch.await(10, TimeUnit.SECONDS)
                if (isClosed) throw java.io.IOException("Stream closed during stall")
            }
            if (pos >= sample.size) return -1
            val count = minOf(len, (sample.size - pos).coerceAtLeast(1), if (!hasStalled) sample.size / 2 else sample.size - pos)
            System.arraycopy(sample, pos, b, off, count)
            pos += count
            return count
        }

        override fun read(): Int {
            val b = ByteArray(1)
            val r = read(b, 0, 1)
            return if (r == -1) -1 else b[0].toInt() and 0xFF
        }

        override fun close() {
            isClosed = true
            stallLatch.countDown()
        }
    }

    private lateinit var backingFile: File
    private lateinit var stallingProvider: StallingContentProvider

    @Before
    fun setUp() {
        DocumentStager.releaseAll()
        StallingContentProvider.reset()
        backingFile = File(application.cacheDir, "stalling_backing.pdf")
        backingFile.writeBytes(samplePdf("backing_doc"))
        stallingProvider = StallingContentProvider(backingFile)
        stallingProvider.attachInfo(application, ProviderInfo().apply { authority = StallingContentProvider.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(StallingContentProvider.AUTHORITY, stallingProvider)
    }

    @After
    fun tearDown() {
        StallingContentProvider.queryLatch?.countDown()
        StallingContentProvider.openLatch?.countDown()
        StallingContentProvider.reset()
        DocumentStager.releaseAll()
        if (::backingFile.isInitialized) {
            backingFile.delete()
        }
    }

    @Test
    fun sc07_01_stalledQuery_doesNotBlockMainThreadLeaseOrUnrelatedSession() = runBlocking {
        // Pre-stage an existing document so we can test lease operations on the Main thread
        val preStaged = DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf("pre_staged")), "pdf")
        assertEquals(0, DocumentStager.activeLeases(preStaged))

        val queryEntered = CountDownLatch(1)
        val queryStallLatch = CountDownLatch(1)
        StallingContentProvider.onQueryEntered = { queryEntered.countDown() }
        StallingContentProvider.queryLatch = queryStallLatch

        val targetUri = Uri.parse("content://${StallingContentProvider.AUTHORITY}/doc.pdf")

        // Launch background staging that stalls in ContentProvider.query
        val stagingJob = async(Dispatchers.IO) {
            DocumentStager.stageDocument(application, targetUri)
        }

        assertTrue("Stager must enter provider query", queryEntered.await(5, TimeUnit.SECONDS))

        // Main thread: retain and release must execute immediately without monitor contention
        val startRetain = System.currentTimeMillis()
        val lease = DocumentStager.retain(preStaged)
        val retainElapsed = System.currentTimeMillis() - startRetain
        assertTrue("Main thread retain must not block on stalled query (took ${retainElapsed}ms)", retainElapsed < 500)
        assertEquals(1, DocumentStager.activeLeases(preStaged))

        val startRelease = System.currentTimeMillis()
        lease.close()
        val releaseElapsed = System.currentTimeMillis() - startRelease
        assertTrue("Main thread lease close must not block on stalled query (took ${releaseElapsed}ms)", releaseElapsed < 500)
        assertEquals(0, DocumentStager.activeLeases(preStaged))

        // Main thread: staging an unrelated stream must also succeed without blocking
        val unrelatedInput = ByteArrayInputStream(samplePdf("unrelated_doc"))
        val unrelatedSnapshot = DocumentStager.stageStream(application, unrelatedInput, "pdf")
        assertNotNull("Unrelated staging must succeed while query is stalled", unrelatedSnapshot)

        // Release the stalled query latch so the background staging completes
        queryStallLatch.countDown()
        val stalledResult = stagingJob.await()
        assertNotNull(stalledResult)

        assertEquals("In-flight slots must return to 0", 0, DocumentStager.activeInFlightSlots)
        assertEquals("In-flight bytes must return to 0", 0L, DocumentStager.activeInFlightBytes)
    }

    @Test
    fun sc07_02_stalledOpen_doesNotBlockMainThreadLeaseOrUnrelatedSession() = runBlocking {
        val preStaged = DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf("pre_staged_2")), "pdf")

        val openEntered = CountDownLatch(1)
        val openStallLatch = CountDownLatch(1)
        StallingContentProvider.onOpenEntered = { openEntered.countDown() }
        StallingContentProvider.openLatch = openStallLatch

        val targetUri = Uri.parse("content://${StallingContentProvider.AUTHORITY}/open_doc.pdf")

        val stagingJob = async(Dispatchers.IO) {
            DocumentStager.stageDocument(application, targetUri)
        }

        assertTrue("Stager must enter provider openFile", openEntered.await(5, TimeUnit.SECONDS))

        // Main thread: retain and release must execute immediately
        val startRetain = System.currentTimeMillis()
        val lease = DocumentStager.retain(preStaged)
        val retainElapsed = System.currentTimeMillis() - startRetain
        assertTrue("Main thread retain must not block on stalled open (took ${retainElapsed}ms)", retainElapsed < 500)

        lease.close()
        assertEquals(0, DocumentStager.activeLeases(preStaged))

        // Staging an unrelated stream must proceed immediately
        val unrelatedInput = ByteArrayInputStream(samplePdf("unrelated_open"))
        val unrelatedSnapshot = DocumentStager.stageStream(application, unrelatedInput, "pdf")
        assertNotNull("Unrelated staging must succeed while open is stalled", unrelatedSnapshot)

        // Unstall openFile
        openStallLatch.countDown()
        val result = stagingJob.await()
        assertNotNull(result)

        assertEquals(0, DocumentStager.activeInFlightSlots)
        assertEquals(0L, DocumentStager.activeInFlightBytes)
    }

    @Test
    fun sc07_03_stalledRead_doesNotBlockMainThreadLeaseOrUnrelatedSession() = runBlocking {
        val preStaged = DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf("pre_staged_3")), "pdf")

        val readStallLatch = CountDownLatch(1)
        val onReadStalled = CountDownLatch(1)
        val stalledStream = StallingInputStream(samplePdf("stalled_read_sample"), readStallLatch, onReadStalled)

        val stagingJob = async(Dispatchers.IO) {
            DocumentStager.stageStreamWithLease(application, stalledStream, "pdf")
        }

        assertTrue("Stager must enter stream read loop and reach mid-stream stall", onReadStalled.await(5, TimeUnit.SECONDS))

        // While stager is paused waiting for more bytes, verify in-flight reservations
        assertEquals("In-flight slot must be reserved", 1, DocumentStager.activeInFlightSlots)
        assertTrue("In-flight bytes must be tracked (> 0)", DocumentStager.activeInFlightBytes > 0)

        // Main thread retain/release and isLeased must execute immediately without monitor contention
        val startLease = System.currentTimeMillis()
        val lease = DocumentStager.retain(preStaged)
        val leaseElapsed = System.currentTimeMillis() - startLease
        assertTrue("Main thread retain must not block during stream read loop (took ${leaseElapsed}ms)", leaseElapsed < 500)
        assertTrue(DocumentStager.isLeased(preStaged.uri))
        lease.close()
        assertFalse(DocumentStager.isLeased(preStaged.uri))

        // Unrelated staging must execute cleanly while stream read is stalled
        val unrelatedSnapshot = DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf("unrelated_read")), "pdf")
        assertNotNull("Unrelated staging must succeed while stream read is stalled", unrelatedSnapshot)

        // Resume stream reading
        readStallLatch.countDown()
        val stagedLease = stagingJob.await()
        assertNotNull(stagedLease.document)
        assertEquals(1, DocumentStager.activeLeases(stagedLease.document))

        stagedLease.close()
        DocumentStager.release(stagedLease.document)

        assertEquals("In-flight slots must return to 0", 0, DocumentStager.activeInFlightSlots)
        assertEquals("In-flight bytes must return to 0", 0L, DocumentStager.activeInFlightBytes)
    }

    @Test
    fun sc07_04_cancellationOfStalledStaging_reclaimsReservationsAndDeletesPartialFiles() = runBlocking {
        val readStallLatch = CountDownLatch(1)
        val onReadStalled = CountDownLatch(1)
        val stalledStream = StallingInputStream(samplePdf("cancel_stream_sample"), readStallLatch, onReadStalled)

        val transferOwner = ResourceTransferOwner<DocumentStager.StagedLease> { it.discard(null) }

        val stagingJob = launch(Dispatchers.IO) {
            DocumentStager.stageStreamCancellationSafe(application, stalledStream, transferOwner)
        }

        assertTrue("Stager must begin reading bytes and reach stall point", onReadStalled.await(5, TimeUnit.SECONDS))

        assertEquals("In-flight slot must be reserved", 1, DocumentStager.activeInFlightSlots)
        assertTrue("In-flight bytes must be tracked", DocumentStager.activeInFlightBytes > 0)

        // Verify partial snapshot file was created on disk
        val stagedDir = File(application.cacheDir, "staged")
        val partialFilesBefore = stagedDir.listFiles().orEmpty().filter { it.name.startsWith("snapshot_") }
        assertTrue("A partial snapshot file must exist during in-flight staging", partialFilesBefore.isNotEmpty())

        // Cancel the staging coroutine while it is stalled in read
        stagingJob.cancelAndJoin()

        // Assert all reservations are immediately reclaimed
        assertEquals("In-flight slots must be 0 after cancellation", 0, DocumentStager.activeInFlightSlots)
        assertEquals("In-flight bytes must be 0L after cancellation", 0L, DocumentStager.activeInFlightBytes)

        // Assert partial files on disk were cleaned up
        val partialFilesAfter = stagedDir.listFiles().orEmpty().filter { it.name.startsWith("snapshot_") }
        assertEquals("Partial snapshot file must be deleted upon cancellation", 0, partialFilesAfter.size)
    }

    @Test
    fun sc07_05_concurrentStaging_enforcesAggregateQuotasAndPreservesAtomicLeasing() = runBlocking {
        // 1. Concurrently stage 16 documents with atomic leases
        val leaseJobs = (0 until 16).map { i ->
            async(Dispatchers.IO) {
                DocumentStager.stageStreamWithLease(application, ByteArrayInputStream(samplePdf("leased_$i")), "pdf")
            }
        }
        val leasedDocs = leaseJobs.awaitAll()
        assertEquals(16, leasedDocs.size)
        leasedDocs.forEach { (doc, _) ->
            assertEquals("Each leased document must have activeLeases = 1", 1, DocumentStager.activeLeases(doc))
        }

        // 2. Concurrently flood with 20 unleased documents
        val unleasedJobs = (0 until 20).map { i ->
            async(Dispatchers.IO) {
                DocumentStager.stageStream(application, ByteArrayInputStream(samplePdf("unleased_$i")), "pdf")
            }
        }
        val unleasedDocs = unleasedJobs.awaitAll()
        assertEquals(20, unleasedDocs.size)

        // 3. Verify eviction protection: none of the 16 leased documents may be evicted
        leasedDocs.forEach { (doc, _) ->
            assertEquals(1, DocumentStager.activeLeases(doc))
            assertNotNull("Leased document must survive concurrent unleased flooding", DocumentStager.stagedFor(doc.uri))
            assertTrue("File on disk must exist", File(doc.uri.path!!).exists())
        }

        // 4. Verify unleased documents are bounded
        val unleasedCount = DocumentStager.stagedCount - 16
        assertTrue("Unleased count ($unleasedCount) must be <= MAX_UNLEASED_DOCUMENTS + 1",
            unleasedCount <= DocumentStager.MAX_UNLEASED_DOCUMENTS + 1)

        // Cleanup
        leasedDocs.forEach { (doc, lease) ->
            lease.close()
            DocumentStager.release(doc)
        }
        assertEquals(0, DocumentStager.activeInFlightSlots)
        assertEquals(0L, DocumentStager.activeInFlightBytes)
    }
}
