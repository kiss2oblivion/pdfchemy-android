package com.pdfchemy.app.security

import android.content.*
import android.os.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.*
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkerIsolationSecurityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private class Binding(val jail: IPdfJailService, val closeBinding: () -> Unit) : Closeable {
        override fun close() = closeBinding()
    }
    private suspend fun bind(): Binding {
        val channel = Channel<IBinder>(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { channel.trySend(requireNotNull(service)) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        assertTrue(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE))
        return Binding(IPdfJailService.Stub.asInterface(withTimeout(10_000) { channel.receive() })) { context.unbindService(connection) }
    }
    private suspend fun call(jail: IPdfJailService, name: String): JSONObject {
        val result = CompletableDeferred<String>()
        OperationScratchBroker(context).use { scratch ->
            jail.executeEngine(name, null, null, "{}", null, "", 0, scratch, object : IPdfJailStringCallback.Stub() {
                override fun onSuccess(resultJson: String) { result.complete(resultJson) }
                override fun onFailure(errorCode: Int, errorMessage: String) { result.completeExceptionally(IllegalStateException("$errorCode $errorMessage")) }
            })
            return JSONObject(withTimeout(10_000) { result.await() })
        }
    }
    @Test fun isolatedUidAndAnonymousScratchAreUsable() = runBlocking {
        bind().use { binding ->
            val identity = call(binding.jail, "DEBUG_IDENTITY")
            assertNotEquals(Process.myUid(), identity.getInt("uid"))
            assertNotEquals(Process.myPid(), identity.getInt("pid"))
            assertTrue(call(binding.jail, "DEBUG_SCRATCH").getBoolean("scratchCapability"))
        }
    }
    @Test fun outputQuotaPlusOneByteFailsAndTruncatesTheRealDestination() = runBlocking {
        val target = File.createTempFile("quota_output_", ".bin", context.cacheDir)
        val hostPid = Process.myPid()
        try {
            try {
                PdfGateway.executeEngine(context, "DEBUG_OUTPUT_OVERFLOW", null, android.net.Uri.fromFile(target), "{}")
                fail("Output quota overflow was accepted")
            } catch (error: Exception) {
                assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("quota", true))
            }
            assertEquals(0, target.length())
            assertEquals(hostPid, Process.myPid())
            bind().use { assertTrue(call(it.jail, "DEBUG_SCRATCH").getBoolean("scratchCapability")) }
        } finally { target.delete() }
    }
    @Test fun batchMissingAndMismatchedMetadataRejectBeforeParsing() = runBlocking {
        val file = File.createTempFile("not_pdf_", ".pdf", context.cacheDir).apply { writeText("not a PDF") }
        val target = File.createTempFile("batch_output_", ".pdf", context.cacheDir)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val invalid = listOf<Pair<Array<String>?, LongArray?>>(
            null to longArrayOf(file.length()), arrayOf(hash) to null,
            emptyArray<String>() to longArrayOf(file.length()), arrayOf(hash) to longArrayOf(),
            arrayOf(hash, hash) to longArrayOf(file.length()), arrayOf(hash) to longArrayOf(file.length(), file.length()),
            arrayOf("z".repeat(64)) to longArrayOf(file.length()), arrayOf(hash) to longArrayOf(0),
            arrayOf("0".repeat(64)) to longArrayOf(file.length()), arrayOf(hash) to longArrayOf(file.length() + 1)
        )
        try {
            bind().use { binding ->
                for ((hashes, sizes) in invalid) {
                    val result = CompletableDeferred<String>()
                    OperationScratchBroker(context).use { scratch ->
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE).use { dest ->
                                binding.jail.executeEngineBatch("MERGE", arrayOf(source), arrayOf(dest), "{}", null, hashes, sizes, scratch, object : IPdfJailStringCallback.Stub() {
                                    override fun onSuccess(resultJson: String) { result.complete("ACCEPTED") }
                                    override fun onFailure(errorCode: Int, errorMessage: String) { result.complete(errorMessage) }
                                })
                                val message = withTimeout(10_000) { result.await() }
                                assertFalse(message, message == "ACCEPTED")
                                assertTrue(message, message.contains("metadata", true) || message.contains("SHA-256", true) || message.contains("size", true))
                                assertEquals(0, target.length())
                            }
                        }
                    }
                }
                // Both positions must fail before even the valid member parses.
                // The non-PDF fixture makes accidental early parsing observable.
                repeat(2) { invalidPosition ->
                    val result = CompletableDeferred<String>()
                    OperationScratchBroker(context).use { scratch ->
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { first ->
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { second ->
                                ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE).use { dest ->
                                    val hashes = arrayOf(hash, hash).apply { this[invalidPosition] = "0".repeat(64) }
                                    binding.jail.executeEngineBatch("MERGE", arrayOf(first, second), arrayOf(dest), "{}", null, hashes, longArrayOf(file.length(), file.length()), scratch, object : IPdfJailStringCallback.Stub() {
                                        override fun onSuccess(resultJson: String) { result.complete("ACCEPTED") }
                                        override fun onFailure(errorCode: Int, errorMessage: String) { result.complete(errorMessage) }
                                    })
                                    val message = withTimeout(10_000) { result.await() }
                                    assertTrue(message, message.contains("SHA-256", true))
                                    assertEquals(0, target.length())
                                }
                            }
                        }
                    }
                }
            }
        } finally { file.delete(); target.delete() }
    }
    @Test(timeout = 180_000) fun blockingWorkerDeadlineKillsPidAndNextBindingRecovers() = runBlocking {
        val hostPid = Process.myPid()
        val oldPid: Int
        bind().use { binding ->
            oldPid = call(binding.jail, "DEBUG_IDENTITY").getInt("pid")
            val death = CompletableDeferred<Unit>()
            binding.jail.asBinder().linkToDeath({ death.complete(Unit) }, 0)
            OperationScratchBroker(context).use { scratch ->
                val start = SystemClock.elapsedRealtime()
                binding.jail.executeEngine("DEBUG_BLOCK", null, null, "{}", null, "", 0, scratch, object : IPdfJailStringCallback.Stub() {
                    override fun onSuccess(resultJson: String) { fail("Blocking operation unexpectedly completed") }
                    override fun onFailure(errorCode: Int, errorMessage: String) {}
                })
                withTimeout(SecurityLimits.WORKER_DEADLINE_MS + 15_000) { death.await() }
                assertTrue(SystemClock.elapsedRealtime() - start <= SecurityLimits.WORKER_DEADLINE_MS + 15_000)
            }
        }
        assertEquals(hostPid, Process.myPid())
        bind().use { binding ->
            val fresh = call(binding.jail, "DEBUG_IDENTITY")
            assertNotEquals(oldPid, fresh.getInt("pid"))
            assertTrue(call(binding.jail, "DEBUG_SCRATCH").getBoolean("scratchCapability"))
        }
    }
    @Test fun blockedWorkerRejectsTwelveRequestsWithoutQueuingAndRecoversAfterAbort() = runBlocking {
        bind().use { binding ->
            val death = CompletableDeferred<Unit>()
            binding.jail.asBinder().linkToDeath({ death.complete(Unit) }, 0)
            OperationScratchBroker(context).use { scratch ->
                binding.jail.executeEngine("DEBUG_BLOCK", null, null, "{}", null, "", 0, scratch, object : IPdfJailStringCallback.Stub() {
                    override fun onSuccess(resultJson: String) { fail("Blocked job unexpectedly completed") }
                    override fun onFailure(errorCode: Int, errorMessage: String) {}
                })
                try {
                    repeat(12) {
                        try { call(binding.jail, "DEBUG_IDENTITY"); fail("An overlapping job was admitted") }
                        catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("429 BUSY")) }
                    }
                } finally { binding.jail.abortWorker() }
                withTimeout(10_000) { death.await() }
            }
        }
        bind().use { assertTrue(call(it.jail, "DEBUG_SCRATCH").getBoolean("scratchCapability")) }
    }
    @Test fun realPdfParsingAndRenderingUseTheStagedSnapshot() = runBlocking {
        val source = File.createTempFile("fixture_", ".pdf", context.cacheDir)
        try {
            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument()
            doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            doc.save(source); doc.close()
            val snapshot = DocumentStager.stageDocument(context, android.net.Uri.fromFile(source))
            try {
                source.writeText("different malicious bytes")
                val json = JSONObject(PdfGateway.executeEngine(context, "GET_PAGE_COUNT", snapshot.uri, null, "{}"))
                assertEquals(1, json.getInt("pageCount"))
                val bitmap = com.pdfchemy.app.logic.PdfEditor.renderPageBitmap(context, snapshot.uri, 0)
                assertNotNull(bitmap)
                SecurityLimits.requirePixels(bitmap!!.width, bitmap.height)
                bitmap.recycle()
            } finally { DocumentStager.release(snapshot) }
        } finally { source.delete() }
    }
}
