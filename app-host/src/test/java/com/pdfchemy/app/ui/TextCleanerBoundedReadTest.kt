package com.pdfchemy.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class TextCleanerBoundedReadTest {
    @Test
    fun readBoundedUtf8Text_acceptsContentWithinLimit() {
        val text = "Hello PDFchemy\nLine 2 – ȘȚ"
        val stream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        assertEquals(text, readBoundedUtf8Text(stream, maxBytes = 64))
    }

    @Test
    fun readBoundedUtf8Text_rejectsOversizedInputBeforeOom() {
        val oversized = ByteArray(1025) { 'A'.code.toByte() }
        assertThrows(OversizedTextInputException::class.java) {
            readBoundedUtf8Text(ByteArrayInputStream(oversized), maxBytes = 1024)
        }
    }
}

