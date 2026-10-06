package com.pdfchemy.app.logic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTextChunkerTest {

    @Test
    fun `chunks text under limit without splitting`() {
        val text = "Short text"
        val chunks = AudioTextChunker.chunkText(text, 100)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `splits on paragraph boundaries`() {
        val text = "Paragraph 1.\n\nParagraph 2."
        // Limit to 15, should split after "\n\n"
        val chunks = AudioTextChunker.chunkText(text, 15)
        assertEquals(2, chunks.size)
        assertEquals("Paragraph 1.", chunks[0])
        assertEquals("Paragraph 2.", chunks[1])
    }

    @Test
    fun `respects surrogate pairs on hard splits`() {
        // "Emoji 😀" where 😀 is a surrogate pair (2 chars)
        // If max length falls exactly on the high surrogate, it should back up.
        val emoji = "\uD83D\uDE00"
        val text = "123$emoji"
        // Length is 5. If limit is 4, it falls exactly on \uD83D
        val chunks = AudioTextChunker.chunkText(text, 4)
        
        // Chunk 1 should be "123", avoiding the split
        assertEquals("123", chunks[0])
        assertEquals(emoji, chunks[1])
    }
}
