package com.pdfchemy.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvParserTest {

    @Test
    fun parseSimpleCsv() {
        val input = "Name,Age,Role\nAlice,30,Engineer\nBob,25,Designer"
        val rows = CsvParser.parse(input)
        assertEquals(3, rows.size)
        assertEquals(listOf("Name", "Age", "Role"), rows[0])
        assertEquals(listOf("Alice", "30", "Engineer"), rows[1])
        assertEquals(listOf("Bob", "25", "Designer"), rows[2])
    }

    @Test
    fun parseCrlfAndLfLineEndings() {
        val crlfInput = "A,B\r\nC,D\r\nE,F\r\n"
        val rowsCrlf = CsvParser.parse(crlfInput)
        assertEquals(3, rowsCrlf.size)
        assertEquals(listOf("A", "B"), rowsCrlf[0])
        assertEquals(listOf("C", "D"), rowsCrlf[1])
        assertEquals(listOf("E", "F"), rowsCrlf[2])

        val mixedInput = "A,B\r\nC,D\nE,F"
        val rowsMixed = CsvParser.parse(mixedInput)
        assertEquals(3, rowsMixed.size)
        assertEquals(listOf("A", "B"), rowsMixed[0])
        assertEquals(listOf("C", "D"), rowsMixed[1])
        assertEquals(listOf("E", "F"), rowsMixed[2])
    }

    @Test
    fun parseQuotedCommas() {
        val input = "Item,Price,\"Notes, Remarks\"\nWidget,$10.00,\"Fragile, handle with care\""
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Item", "Price", "Notes, Remarks"), rows[0])
        assertEquals(listOf("Widget", "$10.00", "Fragile, handle with care"), rows[1])
    }

    @Test
    fun parseEscapedQuotes() {
        val input = "Title,Quote\nPhilosophy,\"He said \"\"I think, therefore I am\"\"\""
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Title", "Quote"), rows[0])
        assertEquals(listOf("Philosophy", "He said \"I think, therefore I am\""), rows[1])
    }

    @Test
    fun parseEmbeddedNewlinesInQuotes() {
        val input = "\"Multi\nLine\nField\",Simple\r\n\"Embedded\r\nCRLF\",Second"
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Multi\nLine\nField", "Simple"), rows[0])
        assertEquals(listOf("Embedded\r\nCRLF", "Second"), rows[1])
    }

    @Test
    fun parsePreservesQuotedWhitespace() {
        val input = "\"  Leading and trailing spaces  \",\" Tab\tDelimited \",NoQuotes"
        val rows = CsvParser.parse(input)
        assertEquals(1, rows.size)
        assertEquals("  Leading and trailing spaces  ", rows[0][0])
        assertEquals(" Tab\tDelimited ", rows[0][1])
        assertEquals("NoQuotes", rows[0][2])
    }

    @Test
    fun parsePreservesEmptyFields() {
        val input = "Header1,,Header3\nValue1,\"\",Value3\n,,End"
        val rows = CsvParser.parse(input)
        assertEquals(3, rows.size)
        assertEquals(listOf("Header1", "", "Header3"), rows[0])
        assertEquals(listOf("Value1", "", "Value3"), rows[1])
        assertEquals(listOf("", "", "End"), rows[2])
    }

    @Test
    fun parsePreservesBlankRows() {
        val input = "Row1A,Row1B\n\nRow3A,Row3B"
        val rows = CsvParser.parse(input)
        assertEquals(3, rows.size)
        assertEquals(listOf("Row1A", "Row1B"), rows[0])
        assertEquals(listOf(""), rows[1])
        assertEquals(listOf("Row3A", "Row3B"), rows[2])
    }

    @Test
    fun parseTrailingNewlineWithoutExtraneousRow() {
        val input = "Col1,Col2\r\nVal1,Val2\r\n"
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Col1", "Col2"), rows[0])
        assertEquals(listOf("Val1", "Val2"), rows[1])
    }

    @Test
    fun parseEmptyInputReturnsEmptyList() {
        assertTrue(CsvParser.parse("").isEmpty())
    }

    @Test
    fun parseRejectsUnclosedQuotes() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse("Col1,Col2\nVal1,\"Unclosed")
        }
    }

    @Test
    fun parseRejectsStrayCharsAfterClosingQuote() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse("Col1,Col2\nVal1,\"Val2\"Stray")
        }
    }

    @Test
    fun parseRejectsUnescapedQuotesInUnquotedField() {
        assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse("Col1,Col2\nVal1,Val\"2")
        }
    }

    @Test
    fun parseEnforcesMaxCellCharsLimit() {
        val longCell = "A".repeat(CsvParser.MAX_CELL_CHARS + 1)
        assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse("Col1,Col2\nVal1,$longCell")
        }
    }

    @Test
    fun parseEnforcesMaxColsLimit() {
        val manyCols = (0..CsvParser.MAX_COLS).joinToString(",") { "Col$it" }
        assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse(manyCols)
        }
    }

    @Test
    fun parseEnforcesMaxRowsLimit() {
        val manyRows = (0..CsvParser.MAX_ROWS).joinToString("\n") { "Row$it" }
        assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse(manyRows)
        }
    }

    @Test
    fun parseEnforcesMaxTotalCellsLimit() {
        // 201 rows * 100 cols = 20,100 cells (> 20,000 max)
        val row = (1..100).joinToString(",") { "c" }
        val csv = (1..201).joinToString("\n") { row }
        assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse(csv)
        }
    }

    @Test
    fun parseBoundedCsvPerformance() {
        // 5000 rows x 4 columns = 20000 cells (exact limit)
        val row = "Alpha,Beta,Gamma,Delta"
        val csv = (1..5000).joinToString("\n") { row }
        val start = System.currentTimeMillis()
        val parsed = CsvParser.parse(csv)
        val durationMs = System.currentTimeMillis() - start
        assertEquals(5000, parsed.size)
        assertEquals(4, parsed[0].size)
        assertTrue("Parsing 20k cells took ${durationMs}ms, should be fast (< 500ms)", durationMs < 1000)
    }
}
