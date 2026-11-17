package com.pdfchemy.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ReadableParagraphTest {
    @Test fun bionicFormattingPreservesUnicodeTextAndWhitespace() {
        val text = "A 😀emoji\tCafe\u0301\nreader  text"
        val annotated = readableParagraph(text, "", null, true)
        assertEquals(text, annotated.text)
        for (span in annotated.spanStyles) {
            assertFalse(span.end < text.length && Character.isLowSurrogate(text[span.end]))
        }
    }

    @Test fun activeSearchHighlightKeepsItsOriginalOffsetsWithBionicMode() {
        val text = "test TEST end"
        for (enabled in listOf(false, true)) {
            val annotated = readableParagraph(text, "test", 5, enabled)
            val highlights = annotated.spanStyles.filter { it.item.background != Color.Unspecified }
            assertEquals(listOf(0 to 4, 5 to 9), highlights.map { it.start to it.end })
            assertEquals(Color(0xFFFF9800), highlights.single { it.start == 5 }.item.background)
            assertEquals(text, annotated.text)
        }
    }

    @Test fun shortAndMissingSearchesDoNotAddHighlightRanges() {
        for (query in listOf("x", "missing")) {
            val text = readableParagraph("visible words", query, null, true)
            assertTrue(text.spanStyles.none { it.item.background != Color.Unspecified })
        }
    }
}
