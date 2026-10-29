package com.pdfchemy.app.logic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.content.FileProvider
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

@RunWith(AndroidJUnit4::class)
class RetainedShareManagementTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val bytes = ByteArray(32768) { (it % 251).toByte() }
    private val issued = mutableListOf<Uri>()
    private fun inspect() = DocumentActions.inspectRetainedShares(context)
    private fun own(tag: String) = inspect().entries.filter { it.displayName.contains(tag) }
    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun remote(method: String, argument: String? = null, extra: Bundle? = null) =
        context.contentResolver.call(Uri.parse("content://com.pdfchemy.app.test.sharerecipient"), method, argument, extra)!!
    private suspend fun share(source: Uri, tag: String, suffix: String) =
        DocumentActions.getShareableContentUriAsync(context, source, "${tag}_$suffix.pdf").also(issued::add)
    private suspend fun awaitNoReaders(tag: String) = withTimeout(10_000) {
        while (own(tag).any { RetainedShareProtection.ACTIVE_READER in it.protections }) yield()
    }
    private suspend fun fixture(block: suspend (Uri, String) -> Unit) {
        val tag = "retained_management_${System.nanoTime()}"
        val lease = DocumentStager.stageStreamWithLease(context, bytes.inputStream(), "pdf")
        try { block(lease.document.uri, tag); assertArrayEquals(bytes, File(lease.document.uri.path!!).readBytes()) }
        finally {
            DocumentActions.copyHookForTesting = null; DocumentActions.retainedDeleteForTesting = null
            remote("close")
            issued.forEach { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            lease.close(); DocumentStager.release(lease.document)
            listOf(File(context.filesDir, "shared_copies"), File(context.cacheDir, "exports")).forEach { root ->
                root.listFiles()?.filter { it.name.contains(tag) }?.forEach { assertTrue(it.delete()) }
            }
        }
    }

    @Test fun inventoryAndSelectedCleanupReportExactLogicalCountsAndBytes() = runBlocking {
        fixture { source, tag ->
            val before = inspect()
            val first = share(source, tag, "first"); val second = share(source, tag, "second")
            val after = inspect()
            assertEquals(before.totalCount + 2, after.totalCount)
            assertEquals(before.totalBytes + 2 * bytes.size, after.totalBytes)
            assertEquals(before.retainedCopyCount + 2, after.retainedCopyCount)
            assertFalse(after.toString().contains(context.filesDir.path))
            val entry = own(tag).single { it.displayName.endsWith("_first.pdf") }
            val result = DocumentActions.cleanupRetainedShares(context, setOf(entry.id))
            assertTrue(result.complete); assertEquals(1, result.deletedCount); assertEquals(bytes.size.toLong(), result.deletedBytes)
            assertTrue(runCatching { read(first) }.isFailure)
            assertArrayEquals(bytes, read(second))
            val repeated = DocumentActions.cleanupRetainedShares(context, setOf(entry.id))
            assertTrue(repeated.complete); assertEquals(setOf(entry.id), repeated.missing)
            assertEquals(before.totalCount + 1, inspect().totalCount)
        }
    }

    @Test fun foreignOpenTransfersStayProtectedAndGrantsAreNeverRevoked() = runBlocking {
        fixture { source, tag ->
            val first = share(source, tag, "first"); val second = share(source, tag, "second")
            val ids = own(tag).map { it.id }.toSet()
            assertEquals("SecurityException", remote("read", first.toString()).getString("error"))
            for (uri in listOf(first, second)) context.grantUriPermission("com.pdfchemy.app.test", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val one = remote("open", "one", Bundle().apply { putString("uri", first.toString()) })
            assertNull(one.getString("error")); assertNotEquals(android.os.Process.myUid(), one.getInt("uid"))
            assertNull(remote("open", "two", Bundle().apply { putString("uri", second.toString()) }).getString("error"))
            assertEquals(2, own(tag).count { RetainedShareProtection.ACTIVE_READER in it.protections })
            val skipped = DocumentActions.cleanupRetainedShares(context, ids)
            assertEquals(0, skipped.deletedCount); assertEquals(ids, skipped.protected.keys); assertFalse(skipped.complete)
            assertArrayEquals(bytes, remote("readOpen", "one").getByteArray("bytes"))
            assertArrayEquals(bytes, remote("readOpen", "two").getByteArray("bytes"))
            assertArrayEquals(bytes, read(first)); assertArrayEquals(bytes, read(second))
            assertTrue("Retained copies must remain immutable even for an owning caller",
                runCatching { context.contentResolver.openOutputStream(first, "wt")!!.close() }.isFailure)
            remote("close"); awaitNoReaders(tag)
            // Closing a transfer never expires the completed copy.
            assertArrayEquals(bytes, remote("read", first.toString()).getByteArray("bytes"))
            awaitNoReaders(tag)
            val cleared = DocumentActions.cleanupRetainedShares(context, ids)
            assertTrue(cleared.complete); assertEquals(2, cleared.deletedCount)
            assertEquals(PackageManager.PERMISSION_GRANTED,
                context.checkUriPermission(first, -1, one.getInt("uid"), Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertEquals("FileNotFoundException", remote("read", first.toString()).getString("error"))
        }
    }

    @Test fun activePartialIsProtectedAndCancellationStillCleansItsOwnCopy() = runBlocking {
        fixture { source, tag ->
            val before = inspect()
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val first = AtomicBoolean(true)
            DocumentActions.copyHookForTesting = {
                if (first.compareAndSet(true, false)) { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            }
            val copying = async(Dispatchers.IO) { share(source, tag, "active") }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val pending = own(tag).single()
                assertEquals(RetainedShareKind.INCOMPLETE_COPY, pending.kind)
                assertEquals(before.activeCopyCount + 1, inspect().activeCopyCount)
                assertEquals(before.activeTransferCount + 1, inspect().activeTransferCount)
                assertTrue(RetainedShareProtection.ACTIVE_COPY in pending.protections)
                assertEquals(before.reservedCopyBytes + bytes.size, inspect().reservedCopyBytes)
                val result = DocumentActions.cleanupRetainedShares(context, setOf(pending.id))
                assertEquals(setOf(pending.id), result.protected.keys); assertEquals(0, result.deletedCount)
                val privatePartial = Uri.Builder().scheme("content").authority("${context.packageName}.fileprovider")
                    .appendPath("retained_share_copies").appendPath(pending.displayName).build()
                assertTrue("An incomplete share has no public readable capability", runCatching { read(privatePartial) }.isFailure)
                copying.cancel(); release.countDown(); copying.join()
                assertTrue(copying.isCancelled); assertTrue(own(tag).isEmpty())
            } finally { release.countDown(); copying.cancelAndJoin() }
        }
    }

    @Test fun deleteFailuresAreReportedAlongsideSuccessfulRemovals() = runBlocking {
        fixture { source, tag ->
            share(source, tag, "ok"); val denied = share(source, tag, "denied"); val error = share(source, tag, "error")
            val ids = own(tag).map { it.id }.toSet()
            DocumentActions.retainedDeleteForTesting = { file -> when {
                file.name.endsWith("_denied.pdf") -> false
                file.name.endsWith("_error.pdf") -> throw IOException("Controlled removal failure")
                else -> file.delete()
            } }
            val result = DocumentActions.cleanupRetainedShares(context, ids)
            assertEquals(1, result.deletedCount); assertEquals(bytes.size.toLong(), result.deletedBytes)
            assertEquals(2, result.failed.size); assertFalse(result.complete)
            assertTrue(result.protected.isEmpty()); assertTrue(result.unprocessed.isEmpty())
            assertEquals(2, own(tag).size)
            assertArrayEquals(bytes, read(denied)); assertArrayEquals(bytes, read(error))
        }
    }

    @Test fun legacyCandidatesAndOriginalsRemainUntouchedByExplicitCleanup() = runBlocking {
        fixture { source, tag ->
            val legacy = File(context.cacheDir, "exports/shared_${tag}_legacy.pdf").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            val original = File(context.filesDir, "scans/${tag}_original.pdf").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            try {
                share(source, tag, "owned")
                val entries = own(tag)
                val candidate = entries.single { it.kind == RetainedShareKind.LEGACY_UNVERIFIED }
                val result = DocumentActions.cleanupRetainedShares(context, entries.map { it.id }.toSet() + "0".repeat(64))
                assertEquals(1, result.deletedCount)
                assertEquals(setOf(RetainedShareProtection.UNVERIFIED_LEGACY_OWNERSHIP), result.protected[candidate.id])
                assertEquals(setOf("0".repeat(64)), result.missing)
                assertArrayEquals(bytes, legacy.readBytes()); assertArrayEquals(bytes, original.readBytes())
                assertTrue(runCatching { DocumentActions.cleanupRetainedShares(context, setOf(source.toString())) }
                    .exceptionOrNull() is IllegalArgumentException)
            } finally { original.delete() }
        }
    }

    @Test fun cancellationAfterOneDeletionReturnsAccuratePartialResult() = runBlocking {
        fixture { source, tag ->
            repeat(3) { share(source, tag, "$it") }
            val ids = own(tag).map { it.id }.toSet(); val cancellation = CancellationSignal()
            DocumentActions.retainedDeleteForTesting = { file -> file.delete().also { cancellation.cancel() } }
            val result = DocumentActions.cleanupRetainedShares(context, ids, cancellation)
            assertTrue(result.cancelled); assertFalse(result.complete)
            assertEquals(1, result.deletedCount); assertEquals(2, result.unprocessed.size)
            assertEquals(ids - result.deleted.keys, result.unprocessed)
            assertEquals(2, own(tag).size); assertTrue(result.failed.isEmpty())
        }
    }

    @Test fun preCancelledCleanupPreservesEverySelectedCopy() = runBlocking {
        fixture { source, tag ->
            val first = share(source, tag, "first"); val second = share(source, tag, "second")
            val ids = own(tag).map { it.id }.toSet()
            val result = DocumentActions.cleanupRetainedShares(context, ids, CancellationSignal().apply { cancel() })
            assertTrue(result.cancelled); assertEquals(0, result.deletedCount); assertEquals(ids, result.unprocessed)
            assertArrayEquals(bytes, read(first)); assertArrayEquals(bytes, read(second))
        }
    }

    @Test fun explicitRemovalRestoresQuotaWithoutEvictingOtherShares() = runBlocking {
        fixture { source, tag ->
            val available = DocumentActions.MAX_RETAINED_SHARE_COPIES - inspect().totalCount
            assertTrue("Validation fixture needs spare capacity", available > 0)
            val copies = (0 until available).map { share(source, tag, "$it") }
            assertTrue(runCatching { share(source, tag, "overflow") }.isFailure)
            val selected = own(tag).single { it.displayName.endsWith("_${available - 1}.pdf") }
            val result = DocumentActions.cleanupRetainedShares(context, setOf(selected.id))
            assertEquals(1, result.deletedCount); assertTrue(result.complete)
            val replacement = share(source, tag, "replacement")
            assertArrayEquals(bytes, read(replacement))
            if (copies.size > 1) assertArrayEquals(bytes, read(copies.first()))
        }
    }

    @Test fun readerDescriptorBudgetRefusesOverflowAndRecoversAfterClose() = runBlocking {
        fixture { source, tag ->
            val uri = share(source, tag, "readers")
            withTimeout(10_000) { while (inspect().activeReaderCount != 0) yield() }
            val readers = mutableListOf<java.io.InputStream>()
            try {
                repeat(DocumentActions.MAX_ACTIVE_SHARE_READERS) { readers.add(context.contentResolver.openInputStream(uri)!!) }
                assertEquals(DocumentActions.MAX_ACTIVE_SHARE_READERS, inspect().activeReaderCount)
                assertTrue(runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.close() }.isFailure)
                val result = DocumentActions.cleanupRetainedShares(context, own(tag).map { it.id }.toSet())
                assertEquals(0, result.deletedCount); assertEquals(1, result.protected.size)
                assertArrayEquals(bytes, readers.first().readBytes())
            } finally { readers.forEach { it.close() } }
            awaitNoReaders(tag)
            assertArrayEquals(bytes, read(uri))
        }
    }

    @Test fun sameShareSurvivesUntilBothForeignReadersClose() = runBlocking {
        fixture { source, tag ->
            val uri = share(source, tag, "two_readers")
            val entry = own(tag).single()
            val before = inspect()
            context.grantUriPermission("com.pdfchemy.app.test", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            for (slot in listOf("one", "two")) {
                val opened = remote("open", slot, Bundle().apply { putString("uri", uri.toString()) })
                assertNull(opened.getString("error"))
                assertNotEquals(android.os.Process.myUid(), opened.getInt("uid"))
            }
            assertEquals(before.activeReaderCount + 2, inspect().activeReaderCount)
            assertEquals(before.activeTransferCount + 2, inspect().activeTransferCount)
            assertEquals(0, DocumentActions.cleanupRetainedShares(context, setOf(entry.id)).deletedCount)
            assertNull(remote("closeOne", "one").getString("error"))
            withTimeout(10_000) { while (inspect().activeReaderCount != before.activeReaderCount + 1) yield() }
            val protected = DocumentActions.cleanupRetainedShares(context, setOf(entry.id))
            assertEquals(setOf(RetainedShareProtection.ACTIVE_READER), protected.protected[entry.id])
            assertArrayEquals(bytes, remote("readOpen", "two").getByteArray("bytes"))
            assertArrayEquals(bytes, read(uri))
            assertNull(remote("closeOne", "two").getString("error"))
            awaitNoReaders(tag)
            // Reader release alone never expires a previously granted URI.
            assertArrayEquals(bytes, remote("read", uri.toString()).getByteArray("bytes"))
            awaitNoReaders(tag)
            assertTrue(DocumentActions.cleanupRetainedShares(context, setOf(entry.id)).complete)
            assertEquals(before.activeTransferCount, inspect().activeTransferCount)
        }
    }
}
