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
        assertEquals("Paragraph 1.\n\n", chunks[0])
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

    @Test fun `preserves Unicode and respects every limit boundary`() {
        val samples = listOf("ASCII paragraph! Next word.", "Română șțăîâ. Altă propoziție!", "中文。下一句。", "😀🚀👩‍💻".repeat(100), "x".repeat(20000))
        for (text in samples) for (limit in listOf(2, 3, 7, 31, 4000)) {
            val chunks = AudioTextChunker.chunkText(text, limit)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length in 1..limit })
            assertTrue(chunks.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
        }
    }
    @Test fun `exact engine limit and adjacent lengths`() {
        for (length in listOf(3999, 4000, 4001, 8000, 8001)) {
            val text = "a".repeat(length)
            val chunks = AudioTextChunker.chunkText(text, 4000)
            assertEquals(text, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= 4000 })
        }
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects impossible Unicode limit`() { AudioTextChunker.chunkText("😀", 1) }

    @Test fun `prefers CJK sentence punctuation without intervening whitespace`() {
        assertEquals(listOf("第一句。", "第二句。"), AudioTextChunker.chunkText("第一句。第二句。", 7))
    }
}
