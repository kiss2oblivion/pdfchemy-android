package com.pdfchemy.app.ui

import com.pdfchemy.app.logic.CsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableExtractorCorrectnessTest {

    @Test
    fun parseCsvHandlesQuotedCommasCorrectly() {
        val input = "Item,Price,\"Notes, Remarks\"\nWidget,$10.00,\"Fragile, handle with care\""
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Item", "Price", "Notes, Remarks"), rows[0])
        assertEquals(listOf("Widget", "$10.00", "Fragile, handle with care"), rows[1])
    }

    @Test
    fun parseCsvHandlesEscapedQuotesCorrectly() {
        val input = "Title,Quote\nPhilosophy,\"He said \"\"I think, therefore I am\"\"\""
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Title", "Quote"), rows[0])
        assertEquals(listOf("Philosophy", "He said \"I think, therefore I am\""), rows[1])
    }

    @Test
    fun parseCsvHandlesEmbeddedNewlinesInsideQuotes() {
        val input = "\"Multi\nLine\nField\",Simple\nSecond,Row"
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Multi\nLine\nField", "Simple"), rows[0])
        assertEquals(listOf("Second", "Row"), rows[1])
    }

    @Test
    fun parseCsvPreservesQuotedWhitespace() {
        val input = "\"  Leading and trailing spaces  \",\" Tab\tDelimited \",NoQuotes"
        val rows = CsvParser.parse(input)
        assertEquals(1, rows.size)
        assertEquals("  Leading and trailing spaces  ", rows[0][0])
        assertEquals(" Tab\tDelimited ", rows[0][1])
        assertEquals("NoQuotes", rows[0][2])
    }

    @Test
    fun parseCsvPreservesEmptyCells() {
        val input = "Header1,,Header3\nValue1,\"\",Value3\n,,End"
        val rows = CsvParser.parse(input)
        assertEquals(3, rows.size)
        assertEquals(listOf("Header1", "", "Header3"), rows[0])
        assertEquals(listOf("Value1", "", "Value3"), rows[1])
        assertEquals(listOf("", "", "End"), rows[2])
    }

    @Test
    fun parseCsvPreservesBlankRows() {
        val input = "Row1A,Row1B\n\nRow3A,Row3B"
        val rows = CsvParser.parse(input)
        assertEquals(3, rows.size)
        assertEquals(listOf("Row1A", "Row1B"), rows[0])
        assertEquals(listOf(""), rows[1]) // Blank row is preserved, not dropped
        assertEquals(listOf("Row3A", "Row3B"), rows[2])
    }

    @Test
    fun parseCsvHandlesTrailingNewlineWithoutExtraRow() {
        val input = "Col1,Col2\r\nVal1,Val2\r\n"
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Col1", "Col2"), rows[0])
        assertEquals(listOf("Val1", "Val2"), rows[1])
    }

    @Test
    fun parseCsvHandlesEmptyInput() {
        val input = ""
        val rows = CsvParser.parse(input)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun latestRequestProtectsAgainstStalePublication() {
        val requests = com.pdfchemy.app.logic.LatestRequest()
        val ticket1 = requests.begin()
        assertTrue(requests.isCurrent(ticket1))

        // Newer request starts
        val ticket2 = requests.begin()
        assertTrue(requests.isCurrent(ticket2))
        assertFalse("Older ticket1 must not be current after ticket2 begins", requests.isCurrent(ticket1))
    }
}
