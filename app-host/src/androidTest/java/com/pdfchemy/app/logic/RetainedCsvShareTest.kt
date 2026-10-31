package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class RetainedCsvShareTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private suspend fun fixture(block: suspend (String) -> Unit) {
        val tag = "retained_csv_${System.nanoTime()}"
        try { block(tag) } finally {
            DocumentActions.copyHookForTesting = null
            File(context.filesDir, "shared_copies").listFiles().orEmpty()
                .filter { it.name.contains(tag) }.forEach { check(it.delete()) }
        }
    }

    @Test fun csvRetainsItsMimeTypeBytesAndReaderProtection() = runBlocking {
        fixture { tag ->
            val csv = "Name,Note\r\nAlpha,\"multiline\nquoted \"\"value\"\" and \u03bb\"\r\n"
            val before = DocumentActions.inspectRetainedShares(context)
            val uri = DocumentActions.createRetainedCsvCopyAsync(context, csv, "$tag.csv")
            assertEquals("content", uri.scheme); assertTrue(uri.lastPathSegment!!.endsWith(".csv"))
            // Android's extension registry uses the legacy CSV alias on some APIs.
            assertTrue(context.contentResolver.getType(uri) in setOf("text/csv", "text/comma-separated-values"))
            val entry = DocumentActions.inspectRetainedShares(context).entries.single { it.displayName.contains(tag) }
            assertEquals(before.retainedCopyCount + 1, DocumentActions.inspectRetainedShares(context).retainedCopyCount)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                assertArrayEquals(csv.toByteArray(Charsets.UTF_8), input.readBytes())
                val cleanup = DocumentActions.cleanupRetainedShares(context, setOf(entry.id))
                assertEquals(0, cleanup.deletedCount)
                assertTrue(RetainedShareProtection.ACTIVE_READER in cleanup.protected.getValue(entry.id))
            }
            context.contentResolver.openInputStream(uri)!!.use {
                assertArrayEquals(csv.toByteArray(Charsets.UTF_8), it.readBytes())
            }
        }
    }

    @Test fun failedCsvCopyLeavesNoRetainedOrIncompleteOutput() = runBlocking {
        fixture { tag ->
            val before = DocumentActions.inspectRetainedShares(context)
            var chunks = 0
            DocumentActions.copyHookForTesting = { if (++chunks == 2) throw IOException("injected CSV copy failure") }
            try {
                DocumentActions.createRetainedCsvCopyAsync(context, "a,b\n".repeat(5_000), "$tag.csv")
                fail("Failed copy must not return a recipient URI")
            } catch (_: IOException) { }
            assertEquals(before.totalCount, DocumentActions.inspectRetainedShares(context).totalCount)
            assertEquals(before.totalBytes, DocumentActions.inspectRetainedShares(context).totalBytes)
            assertEquals(before.reservedCopyBytes, DocumentActions.inspectRetainedShares(context).reservedCopyBytes)
        }
    }

    @Test fun cancelledCsvCopyReleasesReservationsAndPartialOutput() = runBlocking {
        fixture { tag ->
            val before = DocumentActions.inspectRetainedShares(context)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val first = AtomicBoolean(true)
            DocumentActions.copyHookForTesting = {
                if (first.compareAndSet(true, false)) { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
            }
            val copy = async(Dispatchers.IO) { DocumentActions.createRetainedCsvCopyAsync(context, "a,b\n".repeat(5_000), "$tag.csv") }
            try { assertTrue(entered.await(10, TimeUnit.SECONDS)); copy.cancel() }
            finally { release.countDown(); copy.cancelAndJoin() }
            assertTrue(copy.isCancelled)
            assertEquals(before.totalCount, DocumentActions.inspectRetainedShares(context).totalCount)
            assertEquals(before.reservedCopyBytes, DocumentActions.inspectRetainedShares(context).reservedCopyBytes)
        }
    }
}
