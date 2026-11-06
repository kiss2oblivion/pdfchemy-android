package com.pdfchemy.app.security

import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.*
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Actual isolated-worker exports and pre-I/O host admission; no singleton mocking. */
@RunWith(AndroidJUnit4::class)
class BatchIpcIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val maxBatchJson = 32 * 1024
    private val descriptorError = "Batch supports at most 16 input and 16 output files. Use fewer files or select fewer pages."

    private fun pdf(pages: Int): File = File.createTempFile("batch_ipc_", ".pdf", context.cacheDir).also { file ->
        PDDocument().use { document ->
            repeat(pages) { document.addPage(PDPage(PDRectangle(36f, 36f))) }
            document.save(file)
        }
    }

    private fun target(): Uri {
        val id = "batch_" + System.nanoTime()
        val uri = Uri.parse("content://com.pdfchemy.app.security.outputfixture/" + id)
        context.contentResolver.call(uri, "configure", id, Bundle())
        OutputPolicy.registerFreshCopy(uri)
        return uri
    }

    private fun state(uri: Uri): Bundle =
        requireNotNull(context.contentResolver.call(uri, "state", uri.lastPathSegment, null))

    private fun output(uri: Uri): ByteArray {
        val state = requireNotNull(context.contentResolver.call(uri, "awaitWritten", uri.lastPathSegment, null))
        assertEquals(1, state.getInt("opens"))
        return requireNotNull(state.getByteArray("bytes"))
    }

    private fun paddedParams(fields: JSONObject = JSONObject(), size: Int = maxBatchJson): String {
        fields.put("text", "")
        val overhead = fields.toString().toByteArray(Charsets.UTF_8).size
        require(overhead <= size)
        fields.put("text", "x".repeat(size - overhead))
        return fields.toString().also { assertEquals(size, it.toByteArray(Charsets.UTF_8).size) }
    }

    @Test fun sixteenSourcesAndMaximumBatchJsonMergeThroughActualBinder() = runBlocking {
        val source = pdf(1)
        val original = source.readBytes()
        val target = target()
        try {
            PdfGateway.executeEngineBatch(context, "MERGE", List(16) { Uri.fromFile(source) },
                listOf(target), paddedParams())
            PDDocument.load(output(target)).use { assertEquals(16, it.numberOfPages) }
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete() }
    }

    @Test fun sixteenSplitOutputsAndMaximumBatchJsonPublishCompletePdfs() = runBlocking {
        val source = pdf(16)
        val original = source.readBytes()
        val targets = List(16) { target() }
        try {
            val fields = JSONObject().put("pagesToKeep", (1..16).joinToString(","))
            PdfGateway.executeEngineBatch(context, "SPLIT", listOf(Uri.fromFile(source)), targets, paddedParams(fields))
            targets.forEach { uri -> PDDocument.load(output(uri)).use { assertEquals(1, it.numberOfPages) } }
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete() }
    }

    @Test fun sixteenImageOutputsRemainReadable() = runBlocking {
        val source = pdf(16)
        val original = source.readBytes()
        val targets = List(16) { target() }
        try {
            val fields = JSONObject().put("format", "png").put("scale", 0.25)
            PdfGateway.executeEngineBatch(context, "PDF_TO_IMAGES", listOf(Uri.fromFile(source)), targets, paddedParams(fields))
            targets.forEach { uri ->
                val bytes = output(uri)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull("Every published page must decode", bitmap)
                bitmap!!.recycle()
            }
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete() }
    }

    private class NoIoContext(base: Context) : ContextWrapper(base) {
        val touches = AtomicInteger()
        private fun denied(): Nothing {
            touches.incrementAndGet()
            throw AssertionError("Rejected batch reached provider, storage or Binder")
        }
        override fun getApplicationContext(): Context = this
        override fun getContentResolver(): ContentResolver = denied()
        override fun getCacheDir(): File = denied()
        override fun getFilesDir(): File = denied()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = denied()
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean = denied()
    }

    private suspend fun rejectBeforeIo(sources: Int, targets: Int, params: String, expected: String) {
        val source = pdf(1)
        val destination = File.createTempFile("old_batch_target_", ".pdf", context.cacheDir)
        destination.writeText("existing destination bytes")
        val sourceBytes = source.readBytes()
        val destinationBytes = destination.readBytes()
        val guard = NoIoContext(context)
        try {
            val inputUris = List(sources) { Uri.fromFile(source) }
            val outputUris = List(targets) { index ->
                if (index == 0) Uri.fromFile(destination)
                else Uri.parse("content://not-created/batch-target-" + index)
            }
            val error = runCatching {
                PdfGateway.executeEngineBatch(guard, "MERGE", inputUris, outputUris, params)
            }.exceptionOrNull()
            assertTrue("Expected argument rejection, got " + error, error is IllegalArgumentException)
            assertEquals(expected, error!!.message)
            assertEquals("Preflight must precede any provider, scratch or Binder activity", 0, guard.touches.get())
            assertArrayEquals(sourceBytes, source.readBytes())
            assertArrayEquals(destinationBytes, destination.readBytes())
        } finally { source.delete(); destination.delete() }
    }

    @Test fun seventeenthSourceFailsBeforeAnyIoOrBinder() = runBlocking {
        rejectBeforeIo(17, 1, "{}", descriptorError)
    }

    @Test fun seventeenthTargetFailsBeforeAnyIoOrBinder() = runBlocking {
        rejectBeforeIo(1, 17, "{}", descriptorError)
    }

    @Test fun excessBatchJsonFailsBeforeAnyIoOrBinder() = runBlocking {
        rejectBeforeIo(1, 1, paddedParams(size = maxBatchJson + 1), "Batch parameters quota exceeded")
    }

    @Test fun actualAidlProxyMaximumEnvelopeStaysBelowNinetySixKiB() {
        val source = pdf(1)
        val destination = File.createTempFile("batch_wire_", ".tmp", context.cacheDir)
        val measured = AtomicInteger()
        // A different descriptor forces Stub.asInterface to use generated AIDL parceling.
        // Initialize it explicitly: API24's Binder.queryLocalInterface dereferences the descriptor.
        // This measures the envelope, not the kernel's available shared-buffer capacity.
        val recorder = object : Binder() {
            init { attachInterface(null, "com.pdfchemy.app.security.BatchIpcRecorder") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(IBinder.FLAG_ONEWAY, flags and IBinder.FLAG_ONEWAY)
                assertTrue(data.hasFileDescriptors())
                measured.set(data.dataSize())
                return true
            }
        }
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                ParcelFileDescriptor.open(destination, ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                    IPdfJailService.Stub.asInterface(recorder).executeEngineBatch(1L, "PDF_TO_IMAGES",
                        Array(16) { input }, Array(16) { output }, paddedParams(), Binder(),
                        Array(16) { "a".repeat(64) }, LongArray(16) { source.length() }, Binder(),
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) { fail("Recorder must not execute a worker") }
                            override fun onFailure(errorCode: Int, errorMessage: String) { fail(errorMessage) }
                        })
                }
            }
            assertTrue("Unexpected AIDL envelope: " + measured.get(), measured.get() in 1..96 * 1024)
        } finally { source.delete(); destination.delete() }
    }

    private suspend fun <T> withWorker(action: suspend (IPdfJailService) -> T): T {
        val bound = CompletableDeferred<IPdfJailService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound.complete(IPdfJailService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"),
            connection, Context.BIND_AUTO_CREATE))
        try { return action(withTimeout(15_000) { bound.await() }) }
        finally { context.unbindService(connection) }
    }

    @Test fun workerIndependentlyRejectsOversizedBatchJsonWithoutPublication() = runBlocking {
        val source = pdf(1)
        val original = source.readBytes()
        val staged = DocumentStager.stageDocument(context, Uri.fromFile(source))
        val target = target()
        try {
            withWorker { worker ->
                assertNull(worker.asBinder().queryLocalInterface("com.pdfchemy.app.jail.IPdfJailService"))
                OperationScratchBroker(context).use { scratch ->
                    HostOutputTransaction(context, listOf(target), scratch.storage).use { transaction ->
                        val token = worker.beginOperation(scratch)
                        check(token > 0L)
                        try {
                            val terminal = CompletableDeferred<Pair<Int, String>>()
                            context.contentResolver.openFileDescriptor(staged.uri, "r")!!.use { input ->
                                try {
                                    worker.executeEngineBatch(token, "MERGE", arrayOf(input),
                                        transaction.workerDescriptors.toTypedArray(), paddedParams(size = maxBatchJson + 1),
                                        null, arrayOf(staged.sha256), longArrayOf(staged.size), scratch,
                                        object : IPdfJailStringCallback.Stub() {
                                            override fun onSuccess(resultJson: String) { terminal.complete(0 to resultJson) }
                                            override fun onFailure(errorCode: Int, errorMessage: String) {
                                                terminal.complete(errorCode to errorMessage)
                                            }
                                        })
                                } finally { transaction.releaseWorkerDescriptors() }
                                val result = withTimeout(15_000) { terminal.await() }
                                assertEquals(result.second, 400, result.first)
                                assertTrue(result.second, result.second.contains("Batch parameters quota exceeded"))
                            }
                            val state = state(target)
                            assertEquals(0, state.getInt("opens"))
                            assertEquals("existing destination", String(state.getByteArray("bytes")!!))
                        } finally { runCatching { worker.abortOperation(token) } }
                    }
                }
            }
            assertArrayEquals(original, source.readBytes())
        } finally { DocumentStager.release(staged); source.delete() }
    }
}
