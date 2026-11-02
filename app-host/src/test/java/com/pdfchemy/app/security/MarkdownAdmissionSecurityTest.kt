package com.pdfchemy.app.security

import com.pdfchemy.app.logic.MarkdownAdmission
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MarkdownAdmissionSecurityTest {

    @Test
    fun exactByteLimitSucceeds() = runBlocking {
        val budget = 128L
        val data = ByteArray(budget.toInt()) { 'a'.code.toByte() }
        val result = ByteArrayInputStream(data).use {
            MarkdownAdmission.readBoundedText(it, maxBytes = budget)
        }
        assertEquals(budget.toInt(), result.length)
        assertEquals("a".repeat(budget.toInt()), result)
    }

    @Test
    fun bytePlusOneRejectsWithSecurityException() = runBlocking {
        val budget = 128L
        val data = ByteArray((budget + 1).toInt()) { 'a'.code.toByte() }
        try {
            ByteArrayInputStream(data).use {
                MarkdownAdmission.readBoundedText(it, maxBytes = budget)
            }
            fail("Expected SecurityException on byte plus-one")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("byte budget limit") == true)
        }
    }

    @Test
    fun unknownLengthStreamObeysByteLimit() = runBlocking {
        val budget = 64L
        // Custom stream that returns -1 only after 100 bytes, simulating unknown length provider stream
        val readCount = AtomicInteger(0)
        val stream = object : InputStream() {
            override fun read(): Int {
                return if (readCount.getAndIncrement() < 100) 'z'.code else -1
            }
        }
        try {
            stream.use {
                MarkdownAdmission.readBoundedText(it, maxBytes = budget)
            }
            fail("Expected SecurityException on oversized unknown-length stream")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("byte budget limit") == true)
            assertTrue("Should stop reading shortly after limit", readCount.get() <= budget + 1)
        }
    }

    @Test
    fun multibyteUtf8DecodesCorrectlyWithinBudget() = runBlocking {
        // "Salut lume! Știri și mărunțișuri €100 — 🌟"
        val sample = "Salut lume! Știri și mărunțișuri €100 — 🌟"
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val result = ByteArrayInputStream(bytes).use {
            MarkdownAdmission.readBoundedText(it, maxBytes = bytes.size.toLong())
        }
        assertEquals(sample, result)
    }

    @Test
    fun multibyteUtf8ExceedingByteBudgetRejectsCleanly() = runBlocking {
        // "€" is 3 bytes in UTF-8
        val sample = "€€€€€" // 15 bytes
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val budget = 10L // Only 10 bytes allowed (less than 15)
        try {
            ByteArrayInputStream(bytes).use {
                MarkdownAdmission.readBoundedText(it, maxBytes = budget)
            }
            fail("Expected SecurityException on multibyte byte overflow")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("byte budget limit") == true)
        }
    }

    @Test
    fun exactCharacterLimitSucceeds() = runBlocking {
        val text = "abcdef"
        val result = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use {
            MarkdownAdmission.readBoundedText(it, maxBytes = 1024L, maxChars = 6)
        }
        assertEquals("abcdef", result)
    }

    @Test
    fun characterPlusOneRejectsWithSecurityException() = runBlocking {
        val text = "abcdefg" // 7 chars, 7 bytes
        try {
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use {
                MarkdownAdmission.readBoundedText(it, maxBytes = 1024L, maxChars = 6)
            }
            fail("Expected SecurityException on char plus-one")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("character limit") == true)
        }
    }

    @Test
    fun endlessStreamTerminatesSafelyWithoutOom() = runBlocking {
        val budget = 1024L
        val bytesEmitted = AtomicInteger(0)
        val endless = object : InputStream() {
            override fun read(): Int {
                bytesEmitted.incrementAndGet()
                return 'x'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val toFill = minOf(len, 256)
                for (i in 0 until toFill) {
                    b[off + i] = 'x'.code.toByte()
                }
                bytesEmitted.addAndGet(toFill)
                return toFill
            }
        }

        try {
            endless.use {
                MarkdownAdmission.readBoundedText(it, maxBytes = budget)
            }
            fail("Expected SecurityException on endless stream")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("byte budget limit") == true)
            assertTrue("Must terminate near the limit", bytesEmitted.get() <= budget + 512)
        }
    }

    @Test
    fun coroutineCancellationAbortsReadAndClosesStream() = runBlocking {
        val closed = AtomicBoolean(false)
        val blockingStream = object : InputStream() {
            override fun read(): Int {
                Thread.sleep(5)
                return 'a'.code
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(5)
                b[off] = 'a'.code.toByte()
                return 1
            }

            override fun close() {
                closed.set(true)
            }
        }

        var caughtCancellation = false
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                blockingStream.use {
                    MarkdownAdmission.readBoundedText(it, maxBytes = 100_000L)
                }
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        // Allow reading to start, then cancel
        kotlinx.coroutines.delay(30)
        job.cancelAndJoin()

        assertTrue("Cancellation should have interrupted reading", caughtCancellation)
        assertTrue("Stream must be closed on cancellation", closed.get())
    }

    @Test
    fun streamIsClosedOnSecurityException() = runBlocking {
        val closed = AtomicBoolean(false)
        val stream = object : ByteArrayInputStream(ByteArray(200) { 'x'.code.toByte() }) {
            override fun close() {
                super.close()
                closed.set(true)
            }
        }

        try {
            stream.use {
                MarkdownAdmission.readBoundedText(it, maxBytes = 50L)
            }
            fail("Expected SecurityException")
        } catch (_: SecurityException) {}

        assertTrue("Stream must be closed after rejection", closed.get())
    }
}
