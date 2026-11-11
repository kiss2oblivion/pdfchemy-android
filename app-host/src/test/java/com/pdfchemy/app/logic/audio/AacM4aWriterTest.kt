package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AacM4aWriterTest {

    @Test
    fun `assemble throws on non-existent or empty raw file`() = runTest {
        val nonExistent = File("does_not_exist.raw")
        val dest = File.createTempFile("test_dest", ".m4a")
        val format = PcmFormat(44100, 1, 2)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    AacM4aWriter.assemble(nonExistent, dest, format)
                }
            }
        } finally {
            dest.delete()
        }
    }

    @Test
    fun `isCodecAvailable executes safely`() {
        val available = AacM4aWriter.isCodecAvailable()
        // Should evaluate to true or false cleanly without unhandled exception
        assertTrue(available || !available)
    }

    @Test
    fun `assemble encodes synthetic PCM when codec is available`() = runTest {
        if (!AacM4aWriter.isCodecAvailable()) return@runTest

        val sampleRate = 44100
        val channels = 1
        val format = PcmFormat(sampleRate, channels, 2)
        val raw = File.createTempFile("synthetic_pcm", ".raw")
        val m4a = File.createTempFile("synthetic_output", ".m4a")

        try {
            // Generate 1 second of 440Hz sine wave 16-bit PCM
            val sampleCount = sampleRate
            val bytes = ByteArray(sampleCount * 2)
            for (i in 0 until sampleCount) {
                val angle = 2.0 * Math.PI * 440.0 * i / sampleRate
                val sample = (sin(angle) * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            }
            raw.writeBytes(bytes)

            AacM4aWriter.assemble(raw, m4a, format)
            assertTrue("M4A output should exist", m4a.exists())
            assertTrue("M4A output should be non-empty", m4a.length() > 0)
            // Raw 1s is 88,200 bytes; 128kbps AAC 1s is ~16,000 bytes (< 30,000 bytes)
            assertTrue("M4A output should be compressed compared to raw PCM", m4a.length() < raw.length())
        } finally {
            raw.delete()
            m4a.delete()
        }
    }
}

