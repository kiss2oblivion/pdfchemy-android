package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PcmWriterTest {
    private fun temporary() = File.createTempFile("pcm-test", ".raw")

    @Test fun `cancelling state rejects callbacks before service watcher runs`() = runTest {
        val raw = temporary(); var active = true
        val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { Long.MAX_VALUE }, isJobActive = { active })
        val (id, barrier) = writer.beginChunk(0)
        writer.begin(id, 22050, 2, 1)
        active = false
        writer.audio(id, ByteArray(2)); writer.done(id)
        runCurrent(); assertEquals(0L, raw.length())
        writer.invalidate(); assertTrue(barrier.isCancelled)
        runCatching { writer.closeAndJoin() }; raw.delete()
    }

    @Test fun `done waits for flush before next chunk and sink deletion`() = runTest {
        val raw = temporary()
        try {
            val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { Long.MAX_VALUE })
            val (id, barrier) = writer.beginChunk(0)
            writer.begin(id, 22050, 2, 1)
            writer.audio(id, byteArrayOf(1, 2, 3, 4))
            writer.done(id)
            assertFalse(barrier.isCompleted)
            assertThrows(IllegalStateException::class.java) { writer.beginChunk(1) }
            runCurrent()
            assertEquals(4L, barrier.await())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), raw.readBytes())
            val (next, drained) = writer.beginChunk(1)
            writer.begin(next, 22050, 2, 1)
            writer.audio(next, byteArrayOf(5, 6)); writer.done(next)
            runCurrent(); assertEquals(2L, drained.await())
            writer.closeAndJoin()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), raw.readBytes())
        } finally { raw.delete() }
    }

    @Test fun `wrong job generation utterance and late cancellation callbacks are rejected`() = runTest {
        val raw = temporary()
        try {
            val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { Long.MAX_VALUE })
            val (id, barrier) = writer.beginChunk(0)
            writer.begin("wrong/$id", 22050, 2, 1); writer.audio("wrong/$id", ByteArray(4)); writer.done("wrong/$id")
            assertFalse(barrier.isCompleted)
            writer.begin(id, 22050, 2, 1); writer.audio(id, ByteArray(4))
            writer.invalidate()
            writer.audio(id, ByteArray(4)); writer.done(id); writer.begin(id, 44100, 2, 2)
            runCurrent()
            assertTrue(barrier.isCancelled)
            assertEquals(0L, raw.length())
            runCatching { writer.closeAndJoin() }
        } finally { raw.delete() }
    }

    @Test fun `old chunk callbacks do not contaminate next chunk`() = runTest {
        val raw = temporary()
        try {
            val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { Long.MAX_VALUE })
            val (old, first) = writer.beginChunk(0)
            writer.begin(old, 8000, 3, 1); writer.audio(old, byteArrayOf(1)); writer.done(old)
            runCurrent(); first.await()
            val (next, second) = writer.beginChunk(1)
            writer.audio(old, byteArrayOf(99)); writer.done(old)
            writer.begin(next, 8000, 3, 1); writer.audio(next, byteArrayOf(2)); writer.done(next)
            runCurrent(); second.await(); writer.closeAndJoin()
            assertArrayEquals(byteArrayOf(1, 2), raw.readBytes())
        } finally { raw.delete() }
    }

    @Test fun `format change fails instead of mixing PCM`() = runTest {
        val raw = temporary()
        try {
            val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { Long.MAX_VALUE })
            val (id, first) = writer.beginChunk(0)
            writer.begin(id, 22050, 2, 1); writer.audio(id, ByteArray(2)); writer.done(id)
            runCurrent(); first.await()
            val (next, second) = writer.beginChunk(1)
            writer.begin(next, 44100, 2, 1)
            assertTrue(second.isCancelled)
            writer.audio(next, ByteArray(4)); runCurrent()
            runCatching { writer.closeAndJoin() }
            assertEquals(2L, raw.length())
        } finally { raw.delete() }
    }

    @Test fun `writer IO failure releases the waiting barrier`() = runTest {
        val raw = temporary()
        try {
            val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { Long.MAX_VALUE }, {
                object : OutputStream() { override fun write(b: Int) { throw IOException("disk failure") } }
            })
            val (id, barrier) = writer.beginChunk(0)
            writer.begin(id, 22050, 2, 1); writer.audio(id, ByteArray(4)); writer.done(id)
            runCurrent()
            assertTrue(barrier.isCancelled)
            assertTrue(runCatching { writer.closeAndJoin() }.exceptionOrNull() is IOException)
        } finally { raw.delete() }
    }

    @Test fun `unsupported format missing PCM and misaligned frames fail cleanly`() = runTest {
        for (mode in 0..3) {
            val raw = temporary()
            val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { Long.MAX_VALUE })
            val (id, barrier) = writer.beginChunk(0)
            when (mode) {
                0 -> writer.begin(id, 22050, 4, 1)
                1 -> writer.audio(id, ByteArray(2))
                2 -> { writer.begin(id, 22050, 2, 1); writer.done(id) }
                3 -> { writer.begin(id, 22050, 2, 2); writer.audio(id, ByteArray(3)); writer.done(id) }
            }
            assertTrue(barrier.isCancelled); runCurrent()
            runCatching { writer.closeAndJoin() }; raw.delete()
        }
    }

    @Test fun `bounded queue fails instead of consuming unlimited heap`() = runTest {
        val raw = temporary()
        val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { Long.MAX_VALUE })
        val (id, barrier) = writer.beginChunk(0)
        writer.begin(id, 22050, 2, 1)
        repeat(65) { writer.audio(id, ByteArray(2)) }
        assertTrue(barrier.isCancelled); runCurrent()
        runCatching { writer.closeAndJoin() }; raw.delete()
    }

    @Test fun `actual free-space reserve enforced before queueing PCM`() = runTest {
        val raw = temporary()
        val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw, this, { QuotaProjector.FILESYSTEM_RESERVE })
        val (id, barrier) = writer.beginChunk(0)
        writer.begin(id, 22050, 2, 1); writer.audio(id, ByteArray(2))
        assertTrue(barrier.isCancelled); runCurrent()
        runCatching { writer.closeAndJoin() }; assertEquals(0, raw.length().toInt()); raw.delete()
    }
}
