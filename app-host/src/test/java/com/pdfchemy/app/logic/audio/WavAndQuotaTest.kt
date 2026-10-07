package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavAndQuotaTest {
    @Test fun `PCM acceptance and encoding policy`() {
        assertEquals(2, PcmFormat(22050, 1, 2).bytesPerSample)
        assertEquals(1, PcmFormat(8000, 2, 3).bytesPerSample)
        for (encoding in listOf(0, 1, 4, 5)) assertThrows(IllegalArgumentException::class.java) { PcmFormat(22050, 1, encoding) }
        for (rate in listOf(0, -1, 200000)) assertThrows(IllegalArgumentException::class.java) { PcmFormat(rate, 1, 2) }
        for (channels in listOf(0, -1, 3)) assertThrows(IllegalArgumentException::class.java) { PcmFormat(22050, channels, 2) }
    }
    @Test fun `WAV fields and RIFF padding match actual payload`() = runTest {
        for (format in listOf(PcmFormat(44100, 2, 2), PcmFormat(8000, 1, 3))) {
            val raw = File.createTempFile("wav-test", ".raw")
            val wav = File(raw.parentFile, raw.name + ".wav")
            try {
                val size = if (format.encoding == 3) 3 else 12
                val payload = ByteArray(size) { it.toByte() }; raw.writeBytes(payload)
                WavWriter.assemble(raw, wav, format)
                val bytes = wav.readBytes(); val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
                assertEquals(bytes.size - 8, header.getInt(4))
                assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
                assertEquals(1, header.getShort(20).toInt())
                assertEquals(format.channels, header.getShort(22).toInt())
                assertEquals(format.sampleRate, header.getInt(24))
                assertEquals(format.byteRate, header.getInt(28).toLong())
                assertEquals(format.blockAlign, header.getShort(32).toInt())
                assertEquals(format.bitsPerSample, header.getShort(34).toInt())
                assertEquals(size, header.getInt(40))
                assertArrayEquals(payload, bytes.copyOfRange(44, 44 + size))
                assertEquals(44 + size + (size and 1), bytes.size)
            } finally { raw.delete(); wav.delete() }
        }
    }
    @Test fun `classic RIFF overflow and incomplete frame rejected`() {
        val format = PcmFormat(22050, 1, 2)
        assertThrows(IllegalArgumentException::class.java) { WavWriter.header(format, WavWriter.MAX_PCM_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { WavWriter.header(format, 3) }
        assertThrows(IllegalArgumentException::class.java) { WavWriter.header(format, 0) }
        val header = ByteBuffer.wrap(WavWriter.header(format, WavWriter.MAX_PCM_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xffff_fffeL, header.getInt(4).toLong() and 0xffff_ffffL)
    }
    @Test fun `projection uses PCM duration without double counting stereo channels`() {
        val format = PcmFormat(22050, 2, 2)
        val quota = QuotaProjector(1000)
        quota.recordChunk(100, 88200, format)
        assertEquals(882000L, quota.projectedBytes(format))
        assertFalse(quota.isSafe(format, 1_000_000, 0))
        assertTrue(quota.isSafe(format, 3_000_000, 0))
        quota.recordChunk(900, 793800, format)
        assertEquals(882000L, quota.projectedBytes(format))
        assertFalse(quota.isSafe(format, 1_764_000 + QuotaProjector.FILESYSTEM_RESERVE - 1))
        assertTrue(quota.isSafe(format, 1_764_000 + QuotaProjector.FILESYSTEM_RESERVE))
    }
    @Test fun `projection enforces RIFF limit and recalculates observed rate`() {
        val format = PcmFormat(8000, 1, 3)
        val quota = QuotaProjector(1_000_000)
        quota.recordChunk(1, 8000, format)
        assertEquals(8_000_000_000L, quota.projectedBytes(format))
        assertFalse(quota.isSafe(format, Long.MAX_VALUE))
        val small = QuotaProjector(100)
        small.recordChunk(10, 100, format)
        assertEquals(1000L, small.projectedBytes(format))
        small.recordChunk(10, 300, format)
        assertEquals(2000L, small.projectedBytes(format))
    }
}
