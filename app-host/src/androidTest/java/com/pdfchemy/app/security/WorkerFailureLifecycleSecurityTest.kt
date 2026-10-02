package com.pdfchemy.app.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.*
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkerFailureLifecycleSecurityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private suspend fun <T> withWorker(action: suspend (IPdfJailService) -> T): T {
        val bound = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { bound.complete(requireNotNull(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE))
        try { return action(IPdfJailService.Stub.asInterface(withTimeout(15_000) { bound.await() })) }
        finally { context.unbindService(connection) }
    }

    private suspend fun debug(worker: IPdfJailService, engine: String): String {
        val result = CompletableDeferred<String>()
        OperationScratchBroker(context).use { scratch ->
            val token = worker.beginOperation(scratch)
            if (token == 0L) throw IllegalStateException("Jail 429: BUSY")
            worker.executeEngine(token, engine, null, null, "{}", null, "", 0, scratch,
                object : IPdfJailStringCallback.Stub() {
                    override fun onSuccess(resultJson: String) { result.complete(resultJson) }
                    override fun onFailure(errorCode: Int, errorMessage: String) {
                        result.completeExceptionally(IllegalStateException("Jail $errorCode: $errorMessage"))
                    }
                })
            val out = withTimeout(10_000) { result.await() }
            worker.completeOperation(token)
            return out
        }
    }

    private suspend fun pid(worker: IPdfJailService) = JSONObject(debug(worker, "DEBUG_IDENTITY")).getInt("pid")

    private fun pdf() = File.createTempFile("lifecycle_source_", ".pdf", context.cacheDir).also { file ->
        PDDocument().use { document -> repeat(2) { document.addPage(PDPage()) }; document.save(file) }
    }

    private suspend fun expectRecycle(source: File, failOperation: suspend () -> Unit, expectedMessage: String) {
        val hostPid = Process.myPid()
        val oldPid = withWorker { worker ->
            val oldPid = pid(worker)
            val death = CompletableDeferred<Unit>()
            val recipient = IBinder.DeathRecipient { death.complete(Unit) }
            worker.asBinder().linkToDeath(recipient, 0)
            try {
                val failure = runCatching { failOperation() }.exceptionOrNull()
                assertNotNull("Expected the admitted operation to fail", failure)
                assertTrue(failure.toString(), failure?.message.orEmpty().contains(expectedMessage))
                withTimeout(15_000) { death.await() }
                assertFalse(worker.asBinder().isBinderAlive)
            } finally {
                if (worker.asBinder().isBinderAlive) runCatching { worker.abortWorker() }
                runCatching { worker.asBinder().unlinkToDeath(recipient, 0) }
            }
            oldPid
        }
        assertEquals("The host must survive recycling", hostPid, Process.myPid())
        withWorker { fresh ->
            assertNotEquals("The next document requires a new process", oldPid, pid(fresh))
            val metadata = JSONObject(PdfGateway.executeEngine(context, "METADATA_READ", Uri.fromFile(source), null, "{}"))
            assertEquals(2, metadata.getInt("pageCount"))
        }
    }

    @Test fun admittedEngineFailureCallbackRecyclesWorkerAndNextDocumentSucceeds() = runBlocking {
        val source = pdf()
        try {
            expectRecycle(source, { PdfGateway.executeEngine(context, "DEBUG_FAIL", null, null, "{}") },
                "Jail 400: Deliberate admitted engine failure")
        } finally { source.delete() }
    }

    @Test fun legacyWriterFailureCallbackRecyclesWorkerAndNextDocumentSucceeds() = runBlocking {
        val source = pdf()
        val target = File.createTempFile("lifecycle_output_", ".pdf", context.cacheDir).apply { writeText("existing destination") }
        val staged = DocumentStager.stageDocument(context, Uri.fromFile(source))
        try {
            expectRecycle(source, { PdfJailClient.compressPdf(context, staged, Uri.fromFile(target), targetDpi = 0f) }, "Jail 400:")
            assertEquals("existing destination", target.readText())
        } finally { DocumentStager.release(staged); source.delete(); target.delete() }
    }

    @Test fun legacyQueryParserFailureRecyclesWorkerAndNextDocumentSucceeds() = runBlocking {
        val source = pdf()
        val malformed = File.createTempFile("lifecycle_malformed_", ".pdf", context.cacheDir).apply { writeText("not a PDF") }
        val staged = DocumentStager.stageDocument(context, Uri.fromFile(malformed))
        try {
            expectRecycle(source, { PdfJailClient.analyzePdf(context, staged) }, "Jail 400:")
        } finally { DocumentStager.release(staged); source.delete(); malformed.delete() }
    }

    @Test fun hostProtocolRejectionRecyclesWorkerWithoutOpeningDestination() = runBlocking {
        val source = pdf()
        val target = File.createTempFile("lifecycle_quota_", ".pdf", context.cacheDir).apply { writeText("existing destination") }
        try {
            expectRecycle(source, {
                PdfGateway.executeEngine(context, "DEBUG_UNTRUSTED_OUTPUT_OVERFLOW", null, Uri.fromFile(target), "{}")
            }, "quota")
            assertEquals("existing destination", target.readText())
        } finally { source.delete(); target.delete() }
    }

    @Test fun failedWorkerDoesNotReadmitWhileHostRecycleIsPending() = runBlocking {
        withWorker { worker ->
            val death = CompletableDeferred<Unit>()
            worker.asBinder().linkToDeath({ death.complete(Unit) }, 0)
            try {
                assertTrue(runCatching { debug(worker, "DEBUG_FAIL") }.exceptionOrNull()?.message.orEmpty().contains("Jail 400:"))
                assertTrue(runCatching { debug(worker, "DEBUG_IDENTITY") }.exceptionOrNull()?.message.orEmpty().contains("Jail 429: BUSY"))
                assertTrue(worker.asBinder().isBinderAlive)
            } finally {
                if (worker.asBinder().isBinderAlive) runCatching { worker.abortWorker() }
                withTimeout(15_000) { death.await() }
            }
        }
    }

    @Test fun busyGatewayLegacyWritersQueriesAndMalformedBatchPreserveAdmittedPid() = runBlocking {
        val source = pdf()
        val target = File.createTempFile("lifecycle_busy_", ".pdf", context.cacheDir).apply { writeText("existing destination") }
        val staged = DocumentStager.stageDocument(context, Uri.fromFile(source))
        try {
            withWorker { worker ->
                val oldPid = pid(worker)
                val death = CompletableDeferred<Unit>()
                worker.asBinder().linkToDeath({ death.complete(Unit) }, 0)
                OperationScratchBroker(context).use { scratch ->
                    val blockToken = worker.beginOperation(scratch)
                    check(blockToken > 0L)
                    worker.executeEngine(blockToken, "DEBUG_BLOCK", null, null, "{}", null, "", 0, scratch,
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) { fail("Blocking operation completed") }
                            override fun onFailure(errorCode: Int, errorMessage: String) { fail(errorMessage) }
                        })
                    try {
                        val requests: List<suspend () -> Unit> = listOf(
                            { PdfGateway.executeEngine(context, "DEBUG_IDENTITY", null, null, "{}") },
                            { PdfJailClient.compressPdf(context, staged, Uri.fromFile(target)) },
                            { PdfJailClient.exportModifiedPdf(context, staged, Uri.fromFile(target), "{}") },
                            { PdfJailClient.analyzePdf(context, staged) })
                        for (request in requests) {
                            val failure = runCatching { request() }.exceptionOrNull()
                            assertTrue(failure.toString(), failure?.message.orEmpty().contains("Jail 429: BUSY"))
                            assertFalse("BUSY must preserve PID $oldPid", death.isCompleted)
                            assertTrue(worker.asBinder().isBinderAlive)
                        }
                        val batchCode = CompletableDeferred<Int>()
                        worker.executeEngineBatch(0L, "MERGE", null, null, "{}", null, null, null, scratch,
                            object : IPdfJailStringCallback.Stub() {
                                override fun onSuccess(resultJson: String) { batchCode.complete(0) }
                                override fun onFailure(errorCode: Int, errorMessage: String) { batchCode.complete(errorCode) }
                            })
                        assertEquals(429, withTimeout(10_000) { batchCode.await() })
                        // A fresh binding resolves to the same live Binder/PID
                        // while the admitted operation still occupies its gate.
                        withWorker { assertEquals(worker.asBinder(), it.asBinder()) }
                        assertFalse(death.isCompleted)
                        assertEquals("existing destination", target.readText())
                    } finally { worker.abortOperation(blockToken); withTimeout(15_000) { death.await() } }
                }
            }
        } finally { DocumentStager.release(staged); source.delete(); target.delete() }
    }
}
