package com.pdfchemy.app.security

import android.content.*
import android.net.Uri
import android.os.*
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.*
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HostOutputCommitSecurityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun fixture(block: Boolean = false): Uri {
        val id = "output_${System.nanoTime()}"
        context.contentResolver.call(Uri.parse("content://com.pdfchemy.app.security.outputfixture"), "configure", id,
            Bundle().apply { putBoolean("block", block) })
        return Uri.parse("content://com.pdfchemy.app.security.outputfixture/$id")
    }
    private fun state(uri: Uri): Bundle = requireNotNull(context.contentResolver.call(uri, "state", uri.lastPathSegment, null))
    private fun untouched(uri: Uri) {
        assertEquals("A failed worker must never open the provider", 0, state(uri).getInt("opens"))
        assertEquals("existing destination", String(state(uri).getByteArray("bytes")!!))
    }
    private suspend fun bytes(uri: Uri): ByteArray {
        withTimeout(15_000) { while (!state(uri).getBoolean("written")) delay(25) }
        assertEquals("Commit must open each destination exactly once", 1, state(uri).getInt("opens"))
        return state(uri).getByteArray("bytes")!!
    }
    private suspend fun <T> withWorker(action: suspend (IPdfJailService) -> T): T {
        val bound = CompletableDeferred<IPdfJailService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { bound.complete(IPdfJailService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE))
        try { return action(withTimeout(15_000) { bound.await() }) }
        finally { context.unbindService(connection) }
    }
    private fun write(fd: ParcelFileDescriptor, value: String) {
        val bytes = value.toByteArray(); assertEquals(bytes.size, Os.write(fd.fileDescriptor, bytes, 0, bytes.size))
    }
    private suspend fun probe(worker: IPdfJailService, rewrite: Boolean = false): Long {
        val size = CompletableDeferred<Long>()
        worker.debugOutputProbe(rewrite, object : IPdfJailCallback.Stub() {
            override fun onSuccess(outputSizeBytes: Long) { size.complete(outputSizeBytes) }
            override fun onFailure(errorCode: Int, errorMessage: String) { size.completeExceptionally(IllegalStateException(errorMessage)) }
        })
        return withTimeout(5_000) { size.await() }
    }

    @Test fun rawCompromisedWorkerOverflowCannotTouchNonTruncatableProvider() = runBlocking {
        val target = fixture()
        val result = runCatching { PdfGateway.executeEngine(context, "DEBUG_UNTRUSTED_OUTPUT_OVERFLOW", null, target, "{}") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("quota", true))
        untouched(target)
    }

    @Test fun busyRequestsLeaveTheRunningWorkerAndBothDestinationsUntouched() = runBlocking {
        val source = pdf()
        val staged = DocumentStager.stageDocument(context, Uri.fromFile(source))
        val targets = listOf(fixture(), fixture())
        try {
            withWorker { worker ->
                OperationScratchBroker(context).use { scratch ->
                    worker.executeEngine("DEBUG_BLOCK", null, null, "{}", null, "", 0, scratch,
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) { fail("Blocking job completed") }
                            override fun onFailure(errorCode: Int, errorMessage: String) { fail(errorMessage) }
                        })
                    try {
                        val gateway = runCatching { PdfGateway.executeEngine(context, "DEBUG_DUPLICATE_OUTPUT_SUCCESS", null, targets[0], "{}") }
                        assertTrue(gateway.exceptionOrNull()?.message.orEmpty().contains("BUSY"))
                        assertTrue(worker.asBinder().isBinderAlive)
                        val legacy = runCatching { PdfJailClient.compressPdf(context, staged, targets[1]) }
                        assertTrue(legacy.exceptionOrNull()?.message.orEmpty().contains("BUSY"))
                        targets.forEach(::untouched)
                        assertTrue(worker.asBinder().isBinderAlive)
                    } finally { runCatching { worker.abortWorker() } }
                }
            }
        } finally { DocumentStager.release(staged); source.delete() }
    }

    @Test fun workerDeathAndCancellationNeverOpenNonTruncatableProvider() = runBlocking {
        for (cancel in listOf(false, true)) {
            val target = fixture()
            withWorker { worker ->
                val request = async(Dispatchers.IO) { runCatching { PdfGateway.executeEngine(context, "DEBUG_OUTPUT_BLOCK", null, target, "{}") } }
                try {
                    withTimeout(15_000) { while (probe(worker) == 0L) delay(25) }
                    assertEquals(7, probe(worker))
                    untouched(target)
                    if (cancel) request.cancelAndJoin()
                    else { worker.abortWorker(); assertTrue(withTimeout(15_000) { request.await() }.isFailure) }
                    untouched(target)
                } finally {
                    if (worker.asBinder().isBinderAlive) runCatching { worker.abortWorker() }
                    request.cancelAndJoin()
                }
            }
        }
    }

    @Test(timeout = 180_000) fun realWorkerDeadlineLeavesProviderUntouched() = runBlocking {
        val target = fixture()
        val started = SystemClock.elapsedRealtime()
        val result = runCatching { PdfGateway.executeEngine(context, "DEBUG_OUTPUT_BLOCK", null, target, "{}") }
        assertTrue(result.isFailure)
        assertTrue(SystemClock.elapsedRealtime() - started in 110_000..150_000)
        untouched(target)
    }

    @Test fun retainedWorkerFdCannotChangeValidatedBytesDuringProviderCommit() = runBlocking {
        val target = fixture(block = true)
        withWorker { worker ->
            val request = async(Dispatchers.IO) { PdfGateway.executeEngine(context, "DEBUG_RETAIN_OUTPUT", null, target, "{}") }
            try {
                withTimeout(15_000) { while (state(target).getInt("opens") == 0) delay(25) }
                // Provider open proves all host-only snapshots were made first.
                assertEquals("existing destination", String(state(target).getByteArray("bytes")!!))
                assertTrue(probe(worker, true) > 0)
                context.contentResolver.call(target, "release", target.lastPathSegment, null)
                withTimeout(15_000) { request.await() }
                assertEquals("original worker bytes", String(bytes(target)))
            } finally {
                context.contentResolver.call(target, "release", target.lastPathSegment, null)
                request.cancelAndJoin()
            }
        }
    }

    @Test fun duplicateSuccessCallbacksCommitExactlyOnce() = runBlocking {
        val target = fixture()
        PdfGateway.executeEngine(context, "DEBUG_DUPLICATE_OUTPUT_SUCCESS", null, target, "{}")
        assertEquals("original worker bytes", String(bytes(target)))
    }

    @Test fun aggregateOverQuotaIsRejectedBeforeOpeningAnyDestination() = runBlocking {
        val targets = listOf(fixture(), fixture())
        HostOutputTransaction(context, targets).use { transaction ->
            transaction.workerDescriptors.forEach { Os.ftruncate(it.fileDescriptor, SecurityLimits.MAX_OUTPUT_BYTES / 2 + 1) }
            val result = runCatching { transaction.validateAndSnapshot("{\"success\":true}") }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Aggregate output quota"))
            targets.forEach(::untouched)
        }
    }

    @Test fun failedMalformedAndWrongCountResultsAreRejectedBeforeCommit() = runBlocking {
        val invalid = listOf("{\"success\":false}", "{\"success\":\"true\"}", "{\"isSuccess\":false}",
            "{\"error\":\"failure\"}", "false", "null", "{} trailing", "[".repeat(33) + "0" + "]".repeat(33),
            "{\"success\":true,\"renderedCount\":2}")
        for (response in invalid) {
            val target = fixture()
            HostOutputTransaction(context, listOf(target)).use { transaction ->
                write(transaction.workerDescriptors.single(), "temporary")
                assertTrue(response, runCatching { transaction.validateAndSnapshot(response) }.isFailure)
                assertTrue(runCatching { transaction.commit() }.isFailure)
                untouched(target)
            }
        }
    }

    @Test fun snapshotIgnoresWorkerFileOffsetAndCommitCannotBeRepeated() = runBlocking {
        val target = fixture()
        val transaction = HostOutputTransaction(context, listOf(target))
        val workerFd = transaction.workerDescriptors.single()
        try {
            write(workerFd, "original")
            Os.lseek(workerFd.fileDescriptor, 0, OsConstants.SEEK_END)
            transaction.validateAndSnapshot("true")
            Os.ftruncate(workerFd.fileDescriptor, 0)
            write(workerFd, "changed")
            transaction.commit()
            assertEquals("original", String(bytes(target)))
            assertTrue(runCatching { transaction.commit() }.isFailure)
            assertEquals(1, state(target).getInt("opens"))
        } finally { transaction.close() }
        assertFalse(workerFd.fileDescriptor.valid())
        assertTrue(runCatching { transaction.commit() }.isFailure)
    }

    private fun pdf(): File = File.createTempFile("output_source_", ".pdf", context.cacheDir).also { file ->
        PDDocument().use { document -> repeat(2) { document.addPage(PDPage()) }; document.save(file) }
    }

    @Test fun legacyWritersCommitThroughHostSnapshotsAndLeaveFailuresUntouched() = runBlocking {
        val source = pdf()
        val staged = DocumentStager.stageDocument(context, Uri.fromFile(source))
        try {
            val compressed = fixture()
            val size = PdfJailClient.compressPdf(context, staged, compressed)
            val compressedBytes = bytes(compressed)
            assertEquals(compressedBytes.size.toLong(), size)
            PDDocument.load(compressedBytes).use { assertEquals(2, it.numberOfPages) }
            val exported = fixture()
            try { assertTrue(PdfJailClient.exportModifiedPdf(context, staged, exported, "{}")) }
            catch (error: Throwable) { untouched(exported); throw error }
            PDDocument.load(bytes(exported)).use { assertEquals(2, it.numberOfPages) }
            val rejected = fixture()
            assertTrue(runCatching { PdfJailClient.exportModifiedPdf(context, staged, rejected, "{\"0\":false}") }.isFailure)
            untouched(rejected)
        } finally { DocumentStager.release(staged); source.delete() }
    }

    @Test fun realSplitAndPdfToImagesPreserveMultiOutputCommit() = runBlocking {
        val source = pdf()
        try {
            val split = listOf(fixture(), fixture())
            PdfGateway.executeEngineBatch(context, "SPLIT", listOf(Uri.fromFile(source)), split, "{\"pagesToKeep\":\"1,2\"}")
            for (target in split) PDDocument.load(bytes(target)).use { assertEquals(1, it.numberOfPages) }
            val images = listOf(fixture(), fixture())
            PdfGateway.executeEngineBatch(context, "PDF_TO_IMAGES", listOf(Uri.fromFile(source)), images, "{\"scale\":1,\"format\":\"png\"}")
            for (target in images) assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), bytes(target).take(4).toByteArray())
        } finally { source.delete() }
    }

    @Test fun secondBatchOutputFailureDoesNotCommitFirstWorkerOutput() = runBlocking {
        val source = pdf()
        val targets = listOf(fixture(), fixture())
        try {
            // First output is produced before the invalid second page fails.
            val result = runCatching { PdfGateway.executeEngineBatch(context, "SPLIT", listOf(Uri.fromFile(source)), targets, "{\"pagesToKeep\":\"1,999\"}") }
            assertTrue(result.isFailure)
            targets.forEach(::untouched)
        } finally { source.delete() }
    }
}
