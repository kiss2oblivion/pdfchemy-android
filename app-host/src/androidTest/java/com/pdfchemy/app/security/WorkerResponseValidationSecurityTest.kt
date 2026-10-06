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
import com.pdfchemy.app.logic.MetadataReadContract
import com.pdfchemy.app.logic.PageCountContract
import com.pdfchemy.app.logic.SanitizeAuditContract
import com.pdfchemy.app.logic.PdfGateway
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkerResponseValidationSecurityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private suspend fun <T> withWorker(action: suspend (IPdfJailService) -> T): T {
        val bound = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound.complete(requireNotNull(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE))
        try {
            return action(IPdfJailService.Stub.asInterface(withTimeout(15_000) { bound.await() }))
        } finally {
            context.unbindService(connection)
        }
    }

    private suspend fun pid(worker: IPdfJailService) = JSONObject(debug(worker, "DEBUG_IDENTITY")).getInt("pid")

    private suspend fun debug(worker: IPdfJailService, engine: String): String {
        val result = CompletableDeferred<String>()
        OperationScratchBroker(context).use { scratch ->
            val token = worker.beginOperation(scratch)
            check(token > 0L)
            worker.executeEngine(token, engine, null, null, "{}", null, "", 0, scratch,
                object : IPdfJailStringCallback.Stub() {
                    override fun onSuccess(resultJson: String) {
                        worker.completeOperation(token)
                        result.complete(resultJson)
                    }
                    override fun onFailure(errorCode: Int, errorMessage: String) {
                        worker.abortOperation(token)
                        result.completeExceptionally(IllegalStateException("Jail $errorCode: $errorMessage"))
                    }
                })
            return withTimeout(10_000) { result.await() }
        }
    }

    private fun pdf(pages: Int = 2) = File.createTempFile("validation_source_", ".pdf", context.cacheDir).also { file ->
        PDDocument().use { document ->
            repeat(pages) { document.addPage(PDPage()) }
            document.save(file)
        }
    }

    @Test
    fun executeEngineTypedReturnsStrictValidatedContract() = runBlocking {
        val source = pdf(3)
        try {
            val metadata = PdfGateway.executeEngineTyped<MetadataReadContract>(
                context, "METADATA_READ", Uri.fromFile(source), null, "{}"
            )
            assertEquals(3, metadata.pageCount)
            assertFalse(metadata.isEncrypted)

            val pageCount = PdfGateway.executeEngineTyped<PageCountContract>(
                context, "GET_PAGE_COUNT", Uri.fromFile(source), null, "{}"
            )
            assertEquals(3, pageCount.pageCount)
        } finally {
            source.delete()
        }
    }

    @Test
    fun unknownOperationFailsClosedAndRecyclesWorker() = runBlocking {
        val hostPid = Process.myPid()
        val oldPid = withWorker { worker ->
            val oldPid = pid(worker)
            val death = CompletableDeferred<Unit>()
            val recipient = IBinder.DeathRecipient { death.complete(Unit) }
            worker.asBinder().linkToDeath(recipient, 0)
            try {
                val failure = runCatching {
                    PdfGateway.executeEngineTyped<MetadataReadContract>(
                        context, "UNKNOWN_ATTACK_OP", null, null, "{}"
                    )
                }.exceptionOrNull()
                assertNotNull("Expected unknown operation to fail", failure)
                assertTrue(
                    "Failure should indicate security or unknown operation: $failure",
                    failure is SecurityException || failure?.message?.contains("Unknown or unsupported") == true
                )
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
            assertNotEquals("Suspect process must be recycled after security exception", oldPid, pid(fresh))
        }
    }

    @Test
    fun busySemanticsPreservedWithoutAbortingWorker() = runBlocking {
        val source = pdf(2)
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
                            override fun onSuccess(resultJson: String) { fail("Blocking op finished") }
                            override fun onFailure(errorCode: Int, errorMessage: String) {}
                        })
                    try {
                        val failure = runCatching {
                            PdfGateway.executeEngineTyped<MetadataReadContract>(
                                context, "METADATA_READ", Uri.fromFile(source), null, "{}"
                            )
                        }.exceptionOrNull()
                        assertNotNull(failure)
                        assertTrue(failure?.message.orEmpty().contains("Jail 429: BUSY"))
                        assertFalse("BUSY must NOT recycle the admitted worker process", death.isCompleted)
                        assertTrue(worker.asBinder().isBinderAlive)
                    } finally {
                        worker.abortOperation(blockToken)
                        withTimeout(15_000) { death.await() }
                    }
                }
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun realSanitizerWorkerAuditMatchesValidatorContract() = runBlocking {
        val tempDir = File.createTempFile("producer_test_", "", context.cacheDir).apply { delete(); mkdirs() }
        try {
            // 1. Clean PDF real worker audit execution & validation
            val cleanPdf = File(tempDir, "clean.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.save(cleanPdf)
            }
            val cleanContract = PdfGateway.executeEngineTyped<SanitizeAuditContract>(
                context, "SANITIZE_AUDIT", Uri.fromFile(cleanPdf), null, "{}"
            )
            assertTrue(cleanContract.isClean)
            assertEquals(0, cleanContract.threatsFound)
            assertFalse(cleanContract.isEncrypted)
            assertFalse(cleanContract.parseFailed)
            assertEquals(0, cleanContract.jsCount)
            assertEquals(0, cleanContract.launchActionsCount)
            assertEquals(0, cleanContract.attachmentCount)
            assertEquals(0, cleanContract.uriCount)
            assertFalse(cleanContract.hasMetadata)

            // 2. Encrypted PDF real worker audit execution & validation
            val encryptedPdf = File(tempDir, "encrypted.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                val ap = com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission()
                val spp = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner123", "user123", ap)
                spp.encryptionKeyLength = 128
                doc.protect(spp)
                doc.save(encryptedPdf)
            }
            val encryptedContract = PdfGateway.executeEngineTyped<SanitizeAuditContract>(
                context, "SANITIZE_AUDIT", Uri.fromFile(encryptedPdf), null, "{}"
            )
            assertFalse("Encrypted document must not be clean", encryptedContract.isClean)
            assertTrue("Encrypted document must report isEncrypted=true", encryptedContract.isEncrypted)
            assertFalse("Encrypted document must report parseFailed=false", encryptedContract.parseFailed)
            assertEquals(1, encryptedContract.threatsFound)
            assertEquals(0, encryptedContract.jsCount)
            assertEquals(0, encryptedContract.launchActionsCount)
            assertEquals(0, encryptedContract.attachmentCount)
            assertEquals(0, encryptedContract.uriCount)
            assertFalse(encryptedContract.hasMetadata)

            // 3. Corrupt file real worker audit execution & validation
            val corruptPdf = File(tempDir, "corrupt.pdf").apply { writeText("not a real pdf file") }
            val corruptContract = PdfGateway.executeEngineTyped<SanitizeAuditContract>(
                context, "SANITIZE_AUDIT", Uri.fromFile(corruptPdf), null, "{}"
            )
            assertFalse("Corrupt document must not be clean", corruptContract.isClean)
            assertFalse("Corrupt document must report isEncrypted=false", corruptContract.isEncrypted)
            assertTrue("Corrupt document must report parseFailed=true", corruptContract.parseFailed)
            assertEquals(1, corruptContract.threatsFound)
            assertEquals(0, corruptContract.jsCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
