package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ShareCopyIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val bytes = ByteArray(32768) { (it % 251).toByte() }

    private suspend fun fixture(block: suspend (Uri, String) -> Unit) {
        val tag = "integrity_share_${System.nanoTime()}"
        val lease = DocumentStager.stageStreamWithLease(context, bytes.inputStream(), "pdf")
        try { block(lease.document.uri, tag) }
        finally {
            DocumentActions.copyHookForTesting = null
            lease.close()
            DocumentStager.release(lease.document)
            listOf(File(context.cacheDir, "exports"), File(context.filesDir, "shared_copies")).forEach { directory ->
                directory.listFiles()?.filter { it.name.contains(tag) }?.forEach { file ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    assertTrue("Only this fixture's share copy is reclaimed", file.delete())
                }
            }
        }
    }

    private fun reopen(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun copyFiles() = listOf(File(context.cacheDir, "exports"), File(context.filesDir, "shared_copies"))
        .flatMap { it.listFiles().orEmpty().toList() }
    private fun assertNoPartial(tag: String) = assertTrue("Incomplete copies must be removed", copyFiles().none { it.name.contains(tag) })

    @Test fun repeatedSharingRetainsPreviouslyGrantedUriAndOpenConsumer() = runBlocking {
        fixture { source, tag ->
            val first = DocumentActions.getShareableContentUriAsync(context, source, "${tag}_first.pdf")
            context.grantUriPermission("com.pdfchemy.app.test", first, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.contentResolver.openInputStream(first)!!.use { consumer ->
                repeat(8) { index ->
                    val next = DocumentActions.getShareableContentUriAsync(context, source, "${tag}_$index.pdf")
                    assertArrayEquals(bytes, reopen(next))
                }
                assertArrayEquals("Already open receiver retains exact bytes", bytes, consumer.readBytes())
                assertArrayEquals("A still granted URI must reopen after later shares", bytes, reopen(first))
            }
        }
    }

    @Test fun overlappingSharesCannotUnlinkAnActiveWriter() = runBlocking {
        fixture { source, tag ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = AtomicBoolean(true)
            DocumentActions.copyHookForTesting = {
                if (first.compareAndSet(true, false)) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Copy barrier expired" }
                }
            }
            val active = async(Dispatchers.IO) { DocumentActions.getShareableContentUriAsync(context, source, "${tag}_active.pdf") }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val target = listOf(File(context.cacheDir, "exports"), File(context.filesDir, "shared_copies"))
                    .flatMap { it.listFiles().orEmpty().toList() }.single { it.name.endsWith("${tag}_active.pdf") }
                assertTrue(target.setLastModified(1))
                repeat(6) { index -> DocumentActions.getShareableContentUriAsync(context, source, "${tag}_other$index.pdf") }
                DocumentActions.cleanupAbandonedShareCopies(context)
                val retained = target.exists()
                release.countDown()
                val shared = active.await()
                assertTrue("An active copy must not be evicted", retained)
                assertArrayEquals(bytes, reopen(shared))
            } finally { release.countDown(); active.cancelAndJoin() }
        }
    }

    @Test fun failedAsyncCopyReclaimsPartialOutputAndPreservesSource() = runBlocking {
        fixture { source, tag ->
            val reads = AtomicInteger()
            DocumentActions.copyHookForTesting = { if (reads.incrementAndGet() == 2) throw IOException("Injected read failure") }
            assertTrue(runCatching { DocumentActions.getShareableContentUriAsync(context, source, "$tag.pdf") }.exceptionOrNull() is IOException)
            assertArrayEquals(bytes, File(source.path!!).readBytes())
            assertNoPartial(tag)
        }
    }

    @Test fun cancelledAsyncCopyReclaimsPartialOutputAndPreservesSource() = runBlocking {
        fixture { source, tag ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reads = AtomicInteger()
            DocumentActions.copyHookForTesting = {
                if (reads.incrementAndGet() == 2) { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            }
            val active = async(Dispatchers.IO) { DocumentActions.getShareableContentUriAsync(context, source, "$tag.pdf") }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                active.cancel()
                release.countDown()
                active.join()
                assertTrue(active.isCancelled)
                assertArrayEquals(bytes, File(source.path!!).readBytes())
                assertNoPartial(tag)
            } finally { release.countDown(); active.cancelAndJoin() }
        }
    }

    @Test fun failedSynchronousCopyReclaimsPartialAndReleasesSourceLease() = runBlocking {
        fixture { source, tag ->
            val before = DocumentStager.activeLeases(source)
            val reads = AtomicInteger()
            DocumentActions.copyHookForTesting = { if (reads.incrementAndGet() == 2) throw IOException("Injected sync failure") }
            assertTrue(runCatching { DocumentActions.getShareableContentUri(context, source, "$tag.pdf") }.exceptionOrNull() is IOException)
            assertNoPartial(tag)
            assertEquals(before, DocumentStager.activeLeases(source))
            assertArrayEquals(bytes, File(source.path!!).readBytes())
        }
    }

    @Test fun synchronousCopyHonorsCancellationSignalAfterPartialWrite() = runBlocking {
        fixture { source, tag ->
            val signal = android.os.CancellationSignal()
            val reads = AtomicInteger()
            DocumentActions.copyHookForTesting = { if (reads.incrementAndGet() == 2) signal.cancel() }
            val error = runCatching { DocumentActions.getShareableContentUri(context, source, "$tag.pdf", signal) }.exceptionOrNull()
            assertTrue(error is android.os.OperationCanceledException)
            assertNoPartial(tag)
            assertArrayEquals(bytes, File(source.path!!).readBytes())
        }
    }

    @Test fun countBudgetRefusesNewCopyWithoutEvictingAnyCompletedCopy() = runBlocking {
        fixture { source, tag ->
            val retainedBefore = copyFiles().count { it.name.startsWith("shared_") }
            val available = DocumentActions.MAX_RETAINED_SHARE_COPIES - retainedBefore
            assertTrue("Fixture needs at least two free slots", available >= 2)
            val copies = (0 until available).map { index ->
                DocumentActions.getShareableContentUriAsync(context, source, "${tag}_$index.pdf")
            }
            assertTrue(runCatching { DocumentActions.getShareableContentUriAsync(context, source, "${tag}_overflow.pdf") }.isFailure)
            copies.forEach { assertArrayEquals(bytes, reopen(it)) }
            assertArrayEquals(bytes, File(source.path!!).readBytes())
            assertTrue(copyFiles().none { it.name.endsWith("${tag}_overflow.pdf") })
        }
    }

    @Test fun byteBudgetRefusesNewCopyWithoutDeletingRetainedFiles() = runBlocking {
        fixture { source, tag ->
            val directory = File(context.filesDir, "shared_copies").apply { mkdirs() }
            val retained = File(directory, "shared_${tag}_budget.pdf")
            java.io.RandomAccessFile(retained, "rw").use { it.setLength(DocumentActions.MAX_RETAINED_SHARE_BYTES) }
            assertTrue(runCatching { DocumentActions.getShareableContentUriAsync(context, source, "${tag}_overflow.pdf") }.isFailure)
            assertEquals(DocumentActions.MAX_RETAINED_SHARE_BYTES, retained.length())
            assertArrayEquals(bytes, File(source.path!!).readBytes())
        }
    }

    @Test fun cleanupReclaimsAbandonedPartialButKeepsOldCompletedGrant() = runBlocking {
        fixture { source, tag ->
            val completed = DocumentActions.getShareableContentUriAsync(context, source, "$tag.pdf")
            val file = copyFiles().single { it.name.endsWith("$tag.pdf") }
            assertTrue(file.setLastModified(1))
            val abandoned = File(file.parentFile, ".pending_${tag}_abandoned.pdf").apply { writeText("partial"); setLastModified(1) }
            DocumentActions.cleanupAbandonedShareCopies(context)
            assertFalse(abandoned.exists())
            assertArrayEquals(bytes, reopen(completed))
        }
    }

    @Test fun foreignReceiverCanKeepTwoConsumersAndReopenAfterLaterShares() = runBlocking {
        fixture { source, tag ->
            val receiver = Uri.parse("content://com.pdfchemy.app.test.sharerecipient")
            fun remote(method: String, argument: String? = null, extras: android.os.Bundle? = null) =
                requireNotNull(context.contentResolver.call(receiver, method, argument, extras))
            fun shellBytes(command: String): ByteArray = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            ).use { it.readBytes() }
            val first = DocumentActions.getShareableContentUriAsync(context, source, "${tag}_first.pdf")
            val second = DocumentActions.getShareableContentUri(context, source, "${tag}_second.pdf")
            try {
                val denied = remote("read", first.toString())
                assertNotEquals(android.os.Process.myUid(), denied.getInt("uid"))
                assertEquals("SecurityException", denied.getString("error"))
                val shellUid = String(shellBytes("id -u")).trim().toInt()
                assertEquals(2000, shellUid)
                assertNotEquals(denied.getInt("uid"), shellUid)
                listOf(first, second).forEachIndexed { index, uri ->
                    context.grantUriPermission("com.pdfchemy.app.test", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    assertNull(remote("open", "consumer$index", android.os.Bundle().apply { putString("uri", uri.toString()) }).getString("error"))
                }
                // A second real receiving UID reads while the first receiver
                // holds both streams. Only the completed export is granted.
                context.grantUriPermission("com.android.shell", second, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // UiAutomation tokenizes this command directly; shell quote
                // characters would become part of the URI and miss its grant.
                check(second.toString().none { it.isWhitespace() })
                assertArrayEquals(bytes, shellBytes("content read --uri $second"))
                repeat(8) { DocumentActions.getShareableContentUriAsync(context, source, "${tag}_later$it.pdf") }
                DocumentActions.cleanupAbandonedShareCopies(context)
                repeat(2) { assertArrayEquals(bytes, remote("readOpen", "consumer$it").getByteArray("bytes")) }
                assertArrayEquals(bytes, remote("read", first.toString()).getByteArray("bytes"))
                assertArrayEquals(bytes, remote("read", second.toString()).getByteArray("bytes"))
                assertArrayEquals(bytes, shellBytes("content read --uri $second"))
                context.revokeUriPermission(first, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                assertEquals("SecurityException", remote("read", first.toString()).getString("error"))
                assertArrayEquals(bytes, remote("read", second.toString()).getByteArray("bytes"))
                assertArrayEquals(bytes, shellBytes("content read --uri $second"))
                assertFalse(first.toString().contains("staged"))
            } finally { remote("close") }
        }
    }
}
