package com.pdfchemy.app.ui

import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class TextCleanerInputTest {
    @Test
    fun readsUtf8AndAcceptsTheExactQuota() = runBlocking {
        assertEquals("Cafe é", readCleanerText(ByteArrayInputStream("Cafe é".toByteArray())))
        val bytes = ByteArray(SecurityLimits.MAX_TEXT_BYTES) { 'a'.code.toByte() }
        assertEquals(bytes.size, readCleanerText(ByteArrayInputStream(bytes)).length)
    }

    @Test
    fun refusesOversizedProviderAfterOnlyOneDetectionByte() = runBlocking {
        val input = ByteArrayInputStream(ByteArray(SecurityLimits.MAX_TEXT_BYTES + 1000))
        try {
            readCleanerText(input)
            fail("Oversized text accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(999, input.available())
        }
    }

    @Test
    fun cancellationDoesNotConsumeTheProvider() = runBlocking {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val job = Job().apply { cancel() }
        try {
            withContext(job) { readCleanerText(input) }
            fail("Cancellation ignored")
        } catch (_: CancellationException) {
            assertEquals(3, input.available())
        }
    }
}
