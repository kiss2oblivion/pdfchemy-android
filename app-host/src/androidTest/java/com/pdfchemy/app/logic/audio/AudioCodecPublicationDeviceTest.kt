package com.pdfchemy.app.logic.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class AudioCodecPublicationDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun encode(channels: Int, sampleRate: Int) = runBlocking {
        assertTrue("Fixture requires a platform AAC encoder", AacM4aWriter.isCodecAvailable())
        val raw = File.createTempFile("codec-pcm", ".raw", context.cacheDir)
        val out = File.createTempFile("codec-aac", ".m4a", context.cacheDir)
        val bytes = ByteArray(sampleRate * channels * 2)
        for (frame in 0 until sampleRate) {
            val sample = (kotlin.math.sin(frame * 2.0 * Math.PI * 440 / sampleRate) * 16000).toInt()
            for (channel in 0 until channels) {
                val index = (frame * channels + channel) * 2
                bytes[index] = sample.toByte()
                bytes[index + 1] = (sample shr 8).toByte()
            }
        }
        raw.writeBytes(bytes)
        val extractor = MediaExtractor()
        try {
            AacM4aWriter.assemble(raw, out, PcmFormat(sampleRate, channels, 2))
            assertArrayEquals(bytes, raw.readBytes())
            assertTrue(out.length() in 1 until raw.length())
            extractor.setDataSource(out.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(sampleRate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) in 900_000L..1_300_000L)
            extractor.selectTrack(0)
            val packet = ByteBuffer.allocate(64 * 1024)
            var samples = 0
            while (extractor.readSampleData(packet, 0) >= 0) {
                samples++
                if (!extractor.advance()) break
                packet.clear()
            }
            assertTrue("Finalized container must contain encoded packets", samples > 1)
        } finally {
            extractor.release()
            raw.delete()
            out.delete()
        }
    }

    @Test(timeout = 60_000) fun monoPcmProducesACompleteExtractableAacContainer() = encode(1, 24000)
    @Test(timeout = 60_000) fun stereoPcmProducesACompleteExtractableAacContainer() = encode(2, 48000)

    @Test fun cancellationPreservesBothSourceAndAnExistingDestination() = runBlocking {
        val raw = File.createTempFile("codec-cancel", ".raw", context.cacheDir).apply { writeBytes(ByteArray(32)) }
        val out = File.createTempFile("codec-retained", ".m4a", context.cacheDir).apply { writeText("retained") }
        try {
            val error = runCatching {
                coroutineScope {
                    currentCoroutineContext().cancel()
                    AacM4aWriter.assemble(raw, out, PcmFormat(24000,1,2))
                }
            }.exceptionOrNull()
            assertTrue(error is kotlinx.coroutines.CancellationException)
            assertArrayEquals(ByteArray(32), raw.readBytes())
            assertEquals("retained", out.readText())
        } finally { raw.delete(); out.delete() }
    }
}
