package com.pdfchemy.app.logic.audio

import android.media.AudioFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TtsPcmAssemblerTest {

    private lateinit var jobStore: AudioExportJobStore
    private lateinit var jobId: UUID
    private lateinit var tempDir: File
    private lateinit var masterRawFile: File
    private lateinit var finalWavFile: File
    private lateinit var testScope: TestScope

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "tts_test_${UUID.randomUUID()}")
        tempDir.mkdirs()
        masterRawFile = File(tempDir, "master.raw")
        finalWavFile = File(tempDir, "master.wav")
        jobStore = AudioExportJobStore()
        jobId = UUID.randomUUID()
        jobStore.createJob(jobId)
        
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `assembler writes pcm chunks and uses flush barrier correctly`() = testScope.runTest {
        var chunkCompleted = false
        var errorReported: String? = null

        val assembler = TtsPcmAssembler(
            jobId = jobId,
            masterRawFile = masterRawFile,
            jobStore = jobStore,
            coroutineScope = this,
            onChunkComplete = { chunkCompleted = true },
            onError = { _, msg -> errorReported = msg }
        )

        val sampleRate = 44100
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val channelCount = 1

        assembler.onBeginSynthesis("utt1", sampleRate, audioFormat, channelCount)

        val chunk1 = ByteArray(10) { it.toByte() }
        assembler.onAudioAvailable("utt1", chunk1)
        
        assembler.onDone("utt1")
        advanceUntilIdle() // Process coroutines
        
        assertTrue(chunkCompleted)
        assertEquals(null, errorReported)
        assertTrue(masterRawFile.exists())
        assertEquals(10L, masterRawFile.length())

        assembler.finishAndWriteWavHeader(finalWavFile)
        assertTrue(finalWavFile.exists())
        assertEquals(54L, finalWavFile.length()) // 44 header + 10 bytes
    }

    @Test
    fun `assembler ignores callbacks if job is cancelled`() = testScope.runTest {
        var chunkCompleted = false

        val assembler = TtsPcmAssembler(
            jobId = jobId,
            masterRawFile = masterRawFile,
            jobStore = jobStore,
            coroutineScope = this,
            onChunkComplete = { chunkCompleted = true },
            onError = { _, _ -> }
        )

        assembler.onBeginSynthesis("utt1", 44100, AudioFormat.ENCODING_PCM_16BIT, 1)

        // Cancel the job
        jobStore.updateState(jobId, AudioExportState.CANCELLING)

        // Callback arrives late
        val chunk1 = ByteArray(10) { it.toByte() }
        assembler.onAudioAvailable("utt1", chunk1)
        
        assembler.onDone("utt1")
        advanceUntilIdle()

        // master.raw should not contain the 10 bytes
        assertTrue(masterRawFile.exists())
        assertEquals(0L, masterRawFile.length())
        
        assembler.close()
    }
}
