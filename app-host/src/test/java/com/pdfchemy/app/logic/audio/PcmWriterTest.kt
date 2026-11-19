package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

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

    private class PausedOutput(raw: File) : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val output = raw.outputStream()
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Test writer was not released" }
            output.write(bytes, offset, length)
        }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }

    private fun exerciseBackpressure(callbacks: Int, cancel: Boolean, immediateCleanup: Boolean = false) = runBlocking {
        val raw = temporary()
        val sink = PausedOutput(raw)
        val writer = PcmWriter(UUID.randomUUID(), UUID.randomUUID(), raw,
            CoroutineScope(coroutineContext + Dispatchers.IO), { Long.MAX_VALUE }, { sink })
        val (id, drained) = writer.beginChunk(0)
        writer.begin(id, 22050, 2, 1)
        val filled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val producerError = AtomicReference<Throwable>()
        val expected = ByteArray(callbacks * 2) { index ->
            if (index % 2 == 0) (index / 2).toByte() else ((index / 2) ushr 8).toByte()
        }
        val producer = Thread {
            try {
                repeat(callbacks) { index ->
                    writer.audio(id, expected.copyOfRange(index * 2, index * 2 + 2))
                    if (index == 64) filled.countDown()
                }
                writer.done(id)
            } catch (error: Throwable) { producerError.set(error) }
            finally { finished.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue("Consumer must reach the paused real IO write", sink.entered.await(5, TimeUnit.SECONDS))
            assertTrue("Producer must fill the bounded queue", filled.await(5, TimeUnit.SECONDS))
            assertFalse("A full queue must pause the producer", finished.await(100, TimeUnit.MILLISECONDS))
            assertFalse("Drain must wait for real IO", drained.isCompleted)
            if (cancel) {
                val caller = Thread.currentThread()
                val posted = LinkedBlockingQueue<Runnable>()
                val immediateCaller = object : CoroutineDispatcher() {
                    override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== caller
                    override fun dispatch(context: CoroutineContext, block: Runnable) { posted.add(block) }
                }
                val cleaned = CompletableDeferred<Boolean>()
                if (immediateCleanup) launch(immediateCaller, start = CoroutineStart.UNDISPATCHED) {
                    try { drained.await() }
                    catch (_: CancellationException) { /* Cancellation resumes service cleanup. */ }
                    finally {
                        // Models a synchronous engine stop waiting for the callback to return.
                        cleaned.complete(finished.await(1, TimeUnit.SECONDS))
                    }
                }
                writer.invalidate()
                if (immediateCleanup) {
                    // A producer may win deferred completion before posting its resume.
                    // Drive the caller's event loop until that dispatch has arrived.
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!cleaned.isCompleted && System.nanoTime() < deadline) {
                        posted.poll(10, TimeUnit.MILLISECONDS)?.run()
                    }
                    assertTrue("Cleanup continuation must be dispatched", cleaned.isCompleted)
                    assertTrue("Callbacks must be released before immediate service cleanup", cleaned.await())
                }
                assertTrue("Cancellation must release a blocked producer", finished.await(5, TimeUnit.SECONDS))
                assertTrue(drained.isCancelled)
            }
            sink.release.countDown()
            assertTrue("Producer must finish after IO resumes", finished.await(5, TimeUnit.SECONDS))
            assertNull(producerError.get())
            if (cancel) {
                assertTrue(runCatching { writer.closeAndJoin() }.isFailure)
                assertTrue("Only the already-started write may finish", raw.length() <= 2L)
            } else {
                assertEquals(expected.size.toLong(), withTimeout(5_000) { drained.await() })
                writer.closeAndJoin()
                assertArrayEquals(expected, raw.readBytes())
            }
        } finally {
            sink.release.countDown()
            writer.invalidate()
            producer.join(5_000)
            runCatching { writer.closeAndJoin() }
            raw.delete()
        }
    }

    @Test(timeout = 20_000) fun `fast synthesis waits for bounded IO and preserves every PCM frame`() {
        exerciseBackpressure(callbacks = 512, cancel = false)
    }

    @Test(timeout = 20_000) fun `done barrier waits for space behind buffered PCM`() {
        exerciseBackpressure(callbacks = 65, cancel = false)
    }

    @Test(timeout = 20_000) fun `cancellation releases a producer waiting for queue space`() {
        exerciseBackpressure(callbacks = 512, cancel = true)
    }

    @Test(timeout = 20_000) fun `cancellation releases callbacks before immediate drain waiter cleanup`() {
        exerciseBackpressure(callbacks = 512, cancel = true, immediateCleanup = true)
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
