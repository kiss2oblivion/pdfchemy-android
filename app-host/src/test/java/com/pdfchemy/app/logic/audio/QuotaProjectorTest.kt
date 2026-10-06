package com.pdfchemy.app.logic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaProjectorTest {

    @Test
    fun `projected total bytes is calculated correctly based on time and byte rate`() {
        val projector = QuotaProjector(totalCharacters = 1000)
        
        // Let's say 100 characters took 1000ms (1 second), so 0.01 seconds per character
        // And it produced 44100 bytes of audio
        projector.recordChunk(chunkChars = 100, synthesisTimeMs = 1000L, chunkBytes = 44100L)
        
        // Sample rate: 22050, channels: 1, bytesPerSample: 2 => 44100 bytes/sec
        // Remaining characters = 900.
        // Estimated remaining duration = 900 * 0.01 = 9.0 seconds.
        // Estimated remaining bytes = 9.0 * 22050 * 1 * 2 = 9.0 * 44100 = 396,900.
        // Total projected = 44100 (written) + 396900 = 441,000 bytes.
        
        val projected = projector.getProjectedTotalBytes(sampleRate = 22050, channelCount = 1, bytesPerSample = 2)
        assertEquals(441000L, projected)
    }

    @Test
    fun `isProjectionSafe rejects if projected bytes exceed max allowed`() {
        val projector = QuotaProjector(totalCharacters = 1000000) // Huge document
        
        // 1000 chars take 10 seconds, produces 1,000,000 bytes.
        // 0.01 seconds per char.
        projector.recordChunk(chunkChars = 1000, synthesisTimeMs = 10000L, chunkBytes = 1000000L)
        
        // sampleRate = 44100, channels = 2, bytesPerSample = 2 => 176,400 bytes/sec
        // remaining = 999,000 chars
        // remaining duration = 9990 seconds
        // remaining bytes = 9990 * 176400 = 1,762,236,000 bytes (~1.76 GB)
        
        // Let's say max allowed is 1 GB (1,000,000,000 bytes)
        val isSafe = projector.isProjectionSafe(
            sampleRate = 44100,
            channelCount = 2,
            bytesPerSample = 2,
            maxAllowedBytes = 1000000000L
        )
        assertFalse(isSafe)
        
        // Let's say max allowed is 2 GB (2,000,000,000 bytes)
        val isSafe2 = projector.isProjectionSafe(
            sampleRate = 44100,
            channelCount = 2,
            bytesPerSample = 2,
            maxAllowedBytes = 2000000000L
        )
        assertTrue(isSafe2)
    }
}
