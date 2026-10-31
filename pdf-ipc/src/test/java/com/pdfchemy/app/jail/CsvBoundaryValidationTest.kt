package com.pdfchemy.app.jail

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Golden semantics pinned to Gemini PR12 5c1308cc; no host parser implementation is copied here. */
object CsvBoundaryFixtures {
    val valid = listOf(
        "" to emptyList(),
        "Item,Price,\"Notes, Remarks\"\nWidget,10,\"Fragile, handle with care\"" to
            listOf(listOf("Item", "Price", "Notes, Remarks"), listOf("Widget", "10", "Fragile, handle with care")),
        "Title,Quote\nPhilosophy,\"He said \"\"I think, therefore I am\"\"\"" to
            listOf(listOf("Title", "Quote"), listOf("Philosophy", "He said \"I think, therefore I am\"")),
        "\"Multi\nLine\nField\",Simple\nSecond,Row" to
            listOf(listOf("Multi\nLine\nField", "Simple"), listOf("Second", "Row")),
        "\"  spaced  \",\" Tab\t \",NoQuotes" to listOf(listOf("  spaced  ", " Tab\t ", "NoQuotes")),
        "Header1,,Header3\nValue1,\"\",Value3\n,,End" to
            listOf(listOf("Header1", "", "Header3"), listOf("Value1", "", "Value3"), listOf("", "", "End")),
        "Row1A,Row1B\n\nRow3A,Row3B" to listOf(listOf("Row1A", "Row1B"), listOf(""), listOf("Row3A", "Row3B")),
        "Col1,Col2\r\nVal1,Val2\r\n" to listOf(listOf("Col1", "Col2"), listOf("Val1", "Val2")),
        "\"Line1\r\nLine2\",SecondCol\r\nThird,Fourth" to
            listOf(listOf("Line1\r\nLine2", "SecondCol"), listOf("Third", "Fourth")),
        "\r\n,\r" to listOf(listOf(""), listOf("", "")),
        "\"\"" to listOf(listOf("")),
        "a," to listOf(listOf("a", ""))
    )
    val invalid = listOf("\"Unclosed quote", "Val\"ue", "\"Value\"Stray", "\"a\" ", "a\"\"", "\"a\"\"")
}

class CsvBoundaryValidationTest {
    private fun accepted(csv: String) = assertEquals(csv, WorkerResponseValidator.validateCsvTable(csv))
    private fun rejected(csv: String) {
        assertThrows(IllegalArgumentException::class.java) { WorkerResponseValidator.validateCsvTable(csv) }
    }
    @Test fun publishedGeminiValidFixturesRemainUnchanged() { CsvBoundaryFixtures.valid.forEach { accepted(it.first) } }
    @Test fun malformedQuoteFixturesFailClosed() { CsvBoundaryFixtures.invalid.forEach(::rejected) }
    @Test fun embeddedNewlinesCountAsOneLogicalRow() {
        accepted(List(16) { "\"" + "a\n".repeat(500) + "\"" }.joinToString(","))
    }
    @Test fun multilineCellCannotBypassDecodedLengthLimit() { rejected("\"" + "a".repeat(600) + "\n" + "b".repeat(424) + "\"") }
    @Test fun escapedQuotesCountAsOneDecodedCharacter() { accepted("\"" + "\"\"".repeat(1024) + "\"") }
    @Test fun escapedQuotesStillEnforceDecodedLengthLimit() { rejected("\"" + "\"\"".repeat(1025) + "\"") }
    @Test fun plainCellAtLimitAndOneOver() { accepted("a".repeat(1024)); rejected("a".repeat(1025)) }
    @Test fun blankRecordsCountTowardsRowLimit() { accepted("\n".repeat(5000)); rejected("\n".repeat(5001)) }
    @Test fun whitespaceOnlyRecordsCountTowardsRowLimit() { accepted(" \n".repeat(5000)); rejected(" \n".repeat(5001)) }
    @Test fun crlfIsOneRecordSeparator() { accepted("x\r\n".repeat(5000)); rejected("x\r\n".repeat(5001)) }
    @Test fun emptyFieldsCountTowardsColumnLimit() { accepted(",".repeat(127)); rejected(",".repeat(128)) }
    @Test fun totalCellsAtLimitAndOneOver() {
        val prefix = ",,,\n".repeat(4999)
        accepted(prefix + ",,,"); rejected(prefix + ",,,,")
    }
    @Test fun trailingRecordSeparatorAddsNoExtraRecord() { accepted("a\n".repeat(5000)) }
    @Test fun textSizeLimitIsPreserved() { rejected("a".repeat(2 * 1024 * 1024 + 1)) }
    @Test fun malformedFileCannotReturnPreviewText() {
        val file = File.createTempFile("csv_boundary_", ".csv")
        try {
            file.writeText("\"not closed")
            assertThrows(IllegalArgumentException::class.java) { WorkerResponseValidator.parseCsvTableFile(file) }
            assertEquals("\"not closed", file.readText())
        } finally { file.delete() }
    }
    @Test fun oversizedFileCannotReturnPreviewText() {
        val file = File.createTempFile("csv_boundary_", ".csv")
        try {
            file.writeBytes(ByteArray(2 * 1024 * 1024 + 1))
            assertThrows(IllegalArgumentException::class.java) { WorkerResponseValidator.parseCsvTableFile(file) }
        } finally { file.delete() }
    }
}
