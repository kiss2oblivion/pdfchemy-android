package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AacM4aWriterTest {
    private fun invalidInput(bytes: ByteArray, format: PcmFormat) = runBlocking {
        val raw = File.createTempFile("aac-input", ".raw").apply { writeBytes(bytes) }
        val out = File.createTempFile("aac-output", ".m4a")
        try {
            assertTrue(runCatching { AacM4aWriter.assemble(raw, out, format) }.exceptionOrNull()
                is IllegalArgumentException)
            assertArrayEquals(bytes, raw.readBytes())
            assertEquals(0L, out.length())
        } finally { raw.delete(); out.delete() }
    }

    @Test fun emptyRawInputIsRejectedBeforeCodecCreation() =
        invalidInput(byteArrayOf(), PcmFormat(44100, 1, 2))

    @Test fun pcm8CannotBeMislabeledAsPcm16Aac() =
        invalidInput(ByteArray(32), PcmFormat(44100, 1, 3))

    @Test fun incompleteStereoFramesAreRejected() =
        invalidInput(ByteArray(3), PcmFormat(44100, 2, 2))

    @Test fun theInputCannotBeOverwrittenAsItsOwnOutput() = runBlocking {
        val raw = File.createTempFile("aac-source", ".raw").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        try {
            assertTrue(runCatching { AacM4aWriter.assemble(raw, raw, PcmFormat(44100,1,2)) }
                .exceptionOrNull() is IllegalArgumentException)
            assertArrayEquals(byteArrayOf(1,2,3,4), raw.readBytes())
        } finally { raw.delete() }
    }

    @Test fun cancellationPrecedesCodecCreationAndDestinationMutation() = runBlocking {
        val raw = File.createTempFile("aac-source", ".raw").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        val out = File.createTempFile("aac-existing", ".m4a").apply { writeText("retained") }
        try {
            val failure = runCatching {
                coroutineScope {
                    currentCoroutineContext().cancel()
                    AacM4aWriter.assemble(raw, out, PcmFormat(44100,1,2))
                }
            }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.CancellationException)
            assertEquals("retained", out.readText())
            assertArrayEquals(byteArrayOf(1,2,3,4), raw.readBytes())
        } finally { raw.delete(); out.delete() }
    }
}
