package com.pdfchemy.app.logic

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.utils.DocumentStager
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
import java.io.IOException

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ReaderShareAsyncTest.ShadowFileProvider::class])
class ReaderShareAsyncTest {

    @Implements(androidx.core.content.FileProvider::class)
    class ShadowFileProvider {
        companion object {
            @Implementation
            @JvmStatic
            fun getUriForFile(context: android.content.Context, authority: String, file: File): Uri {
                val canonicalFile = file.canonicalFile
                val exports = File(context.cacheDir, "exports").canonicalFile
                val scans = File(context.cacheDir, "scans").canonicalFile
                val retainedPath = File(context.filesDir, "shared_copies").canonicalPath.replace('\\', '/')
                val exportsPath = exports.path.replace('\\', '/')
                val scansPath = scans.path.replace('\\', '/')
                val filePath = canonicalFile.path.replace('\\', '/')

                return when {
                    filePath.startsWith("$retainedPath/") -> Uri.parse("content://$authority/retained_share_copies/${canonicalFile.name}")
                    filePath.startsWith("$exportsPath/") || filePath == exportsPath -> {
                        val rel = filePath.removePrefix(exportsPath).trimStart('/')
                        Uri.parse("content://$authority/shared_exports/$rel")
                    }
                    filePath.startsWith("$scansPath/") || filePath == scansPath -> {
                        val rel = filePath.removePrefix(scansPath).trimStart('/')
                        Uri.parse("content://$authority/scans/$rel")
                    }
                    else -> throw IllegalArgumentException("Failed to find configured root that contains ${file.path}")
                }
            }
        }
    }

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DocumentStager.releaseAll()
        DocumentActions.copyHookForTesting = null
    }

    @After
    fun tearDown() {
        DocumentActions.copyHookForTesting = null
        DocumentStager.releaseAll()
        File(app.cacheDir, "exports").deleteRecursively()
        File(app.cacheDir, "staged").deleteRecursively()
        File(app.filesDir, "shared_copies").deleteRecursively()
    }

    @Test
    fun stagedFileCopiedToRetainedStorageWithLeaseRetention() = runBlocking {
        val content = "TEST_PDF_READER_SHARE_CONTENT_829104".toByteArray()
        val lease = DocumentStager.stageStreamWithLease(app, ByteArrayInputStream(content), "pdf")
        val stagedUri = lease.document.uri

        assertEquals(1, DocumentStager.activeLeases(stagedUri))

        val exportsDir = File(app.filesDir, "shared_copies").apply { mkdirs() }
        val shareUri = DocumentActions.getShareableContentUriAsync(app, stagedUri, "document.pdf")
        assertEquals("content", shareUri.scheme)
        assertTrue(shareUri.authority?.contains("fileprovider") == true)

        // After completion, the temporary lease acquired by getShareableContentUriAsync is released,
        // leaving only the original caller lease (1).
        assertEquals(1, DocumentStager.activeLeases(stagedUri))

        val exportFiles = exportsDir.listFiles { f -> f.name.startsWith("shared_") }
        assertNotNull(exportFiles)
        assertEquals(1, exportFiles!!.size)
        assertArrayEquals(content, exportFiles[0].readBytes())

        lease.close()
        assertEquals(0, DocumentStager.activeLeases(stagedUri))
    }

    @Test
    fun fileAlreadyInConfiguredRootSharesDirectlyWithoutExtraCopy() = runBlocking {
        val exportsDir = File(app.cacheDir, "exports").apply { mkdirs() }
        val directFile = File(exportsDir, "already_exported.pdf").apply {
            writeBytes("ALREADY_IN_EXPORTS".toByteArray())
        }
        val directUri = Uri.fromFile(directFile)

        val shareUri = DocumentActions.getShareableContentUriAsync(app, directUri, "already_exported.pdf")
        assertEquals("content", shareUri.scheme)

        // No new shared_ file should be created because directFile is already in exports root
        val sharedFiles = exportsDir.listFiles { f -> f.name.startsWith("shared_") }
        assertTrue(sharedFiles.isNullOrEmpty())
    }

    @Test
    fun cancelledCopyDeletesIncompleteExportFile() = runBlocking {
        val content = "TEST_CONTENT_FOR_CANCELLATION_CLEANUP".toByteArray()
        val lease = DocumentStager.stageStreamWithLease(app, ByteArrayInputStream(content), "pdf")
        val stagedUri = lease.document.uri

        val exportsDir = File(app.filesDir, "shared_copies").apply { mkdirs() }

        DocumentActions.copyHookForTesting = {
            throw CancellationException("Simulated cancellation during copy")
        }

        try {
            DocumentActions.getShareableContentUriAsync(app, stagedUri, "cancelled_doc.pdf")
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected
        }

        // Verify incomplete export file is not left behind
        val remainingShared = exportsDir.listFiles()?.toList().orEmpty()
        assertTrue("Incomplete export file must be deleted on cancellation, found: $remainingShared", remainingShared.isEmpty())

        // Lease held by getShareableContentUriAsync must be released
        assertEquals(1, DocumentStager.activeLeases(stagedUri))
        lease.close()
    }

    @Test
    fun ioFailureDeletesIncompleteExportFile() = runBlocking {
        val content = "TEST_CONTENT_FOR_IO_FAILURE_CLEANUP".toByteArray()
        val lease = DocumentStager.stageStreamWithLease(app, ByteArrayInputStream(content), "pdf")
        val stagedUri = lease.document.uri

        val exportsDir = File(app.filesDir, "shared_copies").apply { mkdirs() }

        DocumentActions.copyHookForTesting = {
            throw IOException("Simulated disk full or I/O interruption")
        }

        try {
            DocumentActions.getShareableContentUriAsync(app, stagedUri, "failed_doc.pdf")
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected
        }

        val remainingShared = exportsDir.listFiles()?.toList().orEmpty()
        assertTrue("Incomplete export file must be deleted on failure, found: $remainingShared", remainingShared.isEmpty())

        assertEquals(1, DocumentStager.activeLeases(stagedUri))
        lease.close()
    }

    @Test fun cancellationBeforeCallerResumptionRemovesUnreturnedCompleteCopy() = runBlocking<Unit> {
        val lease = DocumentStager.stageStreamWithLease(app, ByteArrayInputStream("Queued share".toByteArray()), "pdf")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val queue = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val caller = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        DocumentActions.copyHookForTesting = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        var delivered = false
        val request = async(caller, start = CoroutineStart.UNDISPATCHED) {
            DocumentActions.getShareableContentUriAsync(app, lease.document.uri, "queued.pdf").also { delivered = true }
        }
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // The producer was blocked until async returned: the caller has
            // necessarily suspended before a successful copy can finish.
            release.countDown()
            val resume = requireNotNull(queue.poll(10, java.util.concurrent.TimeUnit.SECONDS))
            request.cancel()
            resume.run()
            withTimeout(10_000) { request.join() }
            assertFalse(delivered)
            assertTrue(File(app.filesDir, "shared_copies").listFiles().isNullOrEmpty())
            assertEquals(1, DocumentStager.activeLeases(lease.document.uri))
        } finally {
            release.countDown()
            request.cancel()
            while (!request.isCompleted) {
                requireNotNull(queue.poll(10, java.util.concurrent.TimeUnit.SECONDS)) { "Cancelled share did not resume" }.run()
            }
            request.join()
            lease.close()
        }
    }
}
