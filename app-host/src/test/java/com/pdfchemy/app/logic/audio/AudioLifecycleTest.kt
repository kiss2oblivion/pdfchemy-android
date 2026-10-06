package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.util.UUID

class AudioLifecycleTest {
    @Test fun `foreground type matches available platform support`() {
        for (sdk in listOf(24, 25, 26, 28)) assertEquals(0, AudioForegroundPolicy.typeForSdk(sdk))
        for (sdk in listOf(29, 30, 33, 34)) assertEquals(1, AudioForegroundPolicy.typeForSdk(sdk))
        for (sdk in listOf(35, 36)) assertEquals(8192, AudioForegroundPolicy.typeForSdk(sdk))
    }
    @Test fun `normal transitions cannot skip phases and terminal state cannot resurrect`() {
        val store = AudioExportJobStore(); val id = UUID.randomUUID(); store.createJob(id)
        assertFalse(store.updateState(id, AudioExportState.COMPLETED))
        for (state in listOf(AudioExportState.INITIALIZING, AudioExportState.SYNTHESIZING, AudioExportState.ASSEMBLING,
            AudioExportState.PUBLISHING, AudioExportState.COMPLETED)) assertTrue(store.updateState(id, state))
        assertFalse(store.updateState(id, AudioExportState.CANCELLING))
        assertFalse(store.updateState(id, AudioExportState.FAILED))
        assertEquals(AudioExportState.COMPLETED, store.getJob(id)!!.state)
    }
    @Test fun `cancellation invalidates callbacks before stopping engine and writer`() {
        val store = AudioExportJobStore(); val id = UUID.randomUUID(); store.createJob(id)
        store.updateState(id, AudioExportState.INITIALIZING); store.updateState(id, AudioExportState.SYNTHESIZING)
        val order = mutableListOf<String>()
        val abort = AudioExportAbort(id, store,
            { assertEquals(AudioExportState.CANCELLING, store.getJob(id)!!.state); order.add("invalidate") },
            { order.add("tts.stop") }, { order.add("pipeline.cancel") })
        abort.cancel(); abort.cancel()
        assertEquals(listOf("invalidate", "tts.stop", "pipeline.cancel"), order)
        assertFalse(store.updateState(id, AudioExportState.ASSEMBLING))
        assertTrue(store.updateState(id, AudioExportState.CANCELLED))
        assertFalse(store.updateState(id, AudioExportState.COMPLETED))
    }
    @Test fun `service timeout is failed permanently and rejects late cancellation`() {
        val store = AudioExportJobStore(); val id = UUID.randomUUID(); store.createJob(id)
        store.updateState(id, AudioExportState.INITIALIZING)
        var invalidated = false; var stopped = false; var cancelled = false
        val abort = AudioExportAbort(id, store, { invalidated = true }, { stopped = true }, { cancelled = true })
        abort.timeout()
        assertTrue(invalidated && stopped && cancelled)
        assertEquals(AudioExportState.FAILED, store.getJob(id)!!.state)
        assertTrue(store.getJob(id)!!.error!!.message!!.contains("timed out"))
        assertFalse(store.updateState(id, AudioExportState.CANCELLED))
        abort.cancel(); assertEquals(AudioExportState.FAILED, store.getJob(id)!!.state)
    }
    @Test fun `stale stages removed and active stage preserved within cache boundary`() {
        val cache = File(System.getProperty("java.io.tmpdir"), "audio-stage-${UUID.randomUUID()}"); cache.mkdirs()
        try {
            val staging = AudioStagingManager(cache)
            val active = UUID.randomUUID(); val stale = UUID.randomUUID()
            staging.stageText(active, " Știință\r\n\r\n😀 ")
            staging.stageText(stale, "old text")
            assertEquals("Știință\n\n😀", staging.getTextArtifactFile(active).readText())
            assertEquals(File(cache, "audio-export/$active"), staging.getJobDir(active))
            val sentinel = File(cache, "unrelated.txt"); sentinel.writeText("keep")
            staging.sweepStaleJobs(setOf(active))
            assertFalse(File(cache, "audio-export/$stale").exists())
            assertTrue(staging.getTextArtifactFile(active).exists()); assertTrue(sentinel.exists())
            staging.clearJobStaging(active); assertFalse(File(cache, "audio-export/$active").exists())
            assertThrows(IllegalArgumentException::class.java) { staging.stageText(UUID.randomUUID(), " ") }
        } finally { cache.deleteRecursively() }
    }
    @Test fun `SAF publication failure closes stream and deletes partial destination`() = runTest {
        val wav = File.createTempFile("audio-publish", ".wav"); wav.writeBytes(ByteArray(100))
        try {
            var closed = false; var deleted = false
            val error = runCatching {
                AudioOutputPublisher.copy(wav, {
                    object : OutputStream() {
                        override fun write(b: Int) { throw IOException("provider failed") }
                        override fun close() { closed = true }
                    }
                }, { deleted = true })
            }.exceptionOrNull()
            assertTrue(error is IOException); assertTrue(closed && deleted)
            deleted = false
            assertTrue(runCatching { AudioOutputPublisher.copy(wav, { null }, { deleted = true }) }.isFailure)
            assertTrue(deleted)
        } finally { wav.delete() }
    }
    @Test fun `SAF copy cancellation deletes partial output`() = runTest {
        val wav = File.createTempFile("audio-cancel", ".wav"); wav.writeBytes(ByteArray(200_000))
        try {
            var deleted = false; var closed = false
            val task = launch {
                val context = currentCoroutineContext()
                AudioOutputPublisher.copy(wav, {
                    object : OutputStream() {
                        override fun write(b: Int) = Unit
                        override fun write(b: ByteArray, off: Int, len: Int) { context.cancel() }
                        override fun close() { closed = true }
                    }
                }, { deleted = true })
            }
            task.join(); assertTrue(task.isCancelled); assertTrue(deleted && closed)
        } finally { wav.delete() }
    }
}
