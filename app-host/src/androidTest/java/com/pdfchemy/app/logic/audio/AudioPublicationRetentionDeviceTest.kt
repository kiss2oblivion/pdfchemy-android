package com.pdfchemy.app.logic.audio

import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.OutputPolicy
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AudioPublicationRetentionDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val base = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
    private lateinit var destination: Uri
    private lateinit var wav: File
    private lateinit var expected: ByteArray
    private val store = AudioExportJobStore()
    private val id = UUID.randomUUID()

    @Before fun setup() {
        val state = context.contentResolver.call(base, "configure", null, null)!!
        assertNotEquals(Process.myUid(), state.getInt("providerUid"))
        context.contentResolver.call(base, "release", null, null)
        val tree = DocumentsContract.buildTreeDocumentUri(base.authority!!, "root")
        val directory = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        destination = OutputPolicy.createCopy(directory, "audio/wav", "retention.wav").uri
        expected = WavWriter.header(PcmFormat(22050, 1, AudioFormat.ENCODING_PCM_16BIT), 4) + byteArrayOf(1, 2, 3, 4)
        wav = File.createTempFile("audio_retention_", ".wav", context.cacheDir).apply { writeBytes(expected) }
        store.createJob(id)
        for (phase in listOf(AudioExportState.INITIALIZING, AudioExportState.SYNTHESIZING,
            AudioExportState.ASSEMBLING, AudioExportState.PUBLISHING)) assertTrue(store.updateState(id, phase))
    }

    @After fun cleanup() {
        if (::destination.isInitialized) OutputPolicy.deleteIfFresh(context, destination)
        if (::wav.isInitialized) wav.delete()
        store.removeJob(id)
    }

    private fun verifyRetained() {
        assertEquals(destination.toString(), store.getJob(id)!!.outputUri)
        assertEquals(AudioExportState.CANCELLED, store.getJob(id)!!.state)
        assertFalse(OutputPolicy.isFreshCopy(destination))
        repeat(2) { AudioOutputPublisher.deletePartial(context, destination, isFreshCopy = true) }
        assertArrayEquals(expected, context.contentResolver.openInputStream(destination)!!.use { it.readBytes() })
        assertArrayEquals(expected, wav.readBytes())
        assertEquals(0, context.contentResolver.call(base, "state", null, null)!!.getInt("deletedCount"))
    }

    @Test fun staleCleanupPreservesARealSafWavAndItsReceipt() = runBlocking {
        AudioOutputPublisher.publish(context, wav, destination) {
            assertTrue(store.recordPublication(id, it.toString()))
        }
        store.updateState(id, AudioExportState.CANCELLING)
        store.updateState(id, AudioExportState.CANCELLED)
        verifyRetained()
    }

    @Test fun cancellationAtTheCommitCallbackPreservesARealSafWav() = runBlocking {
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failure.set(error) })
        val job = scope.launch {
            val publishingJob = currentCoroutineContext().job
            AudioOutputPublisher.publish(context, wav, destination) {
                assertTrue(store.recordPublication(id, it.toString()))
                store.updateState(id, AudioExportState.CANCELLING)
                publishingJob.cancel()
            }
        }
        try {
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertNull(failure.get())
            store.updateState(id, AudioExportState.CANCELLED)
            verifyRetained()
        } finally { scope.cancel() }
    }
}
