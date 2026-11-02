package com.pdfchemy.app.jail

import com.pdfchemy.app.logic.CsvParser
import org.junit.Assert.*
import org.junit.Test

/** Runs both implementations from the actual integrated source, without a frozen parser copy. */
class IntegratedCsvParityTest {
    private fun assertParity(csv: String, valid: Boolean? = null) {
        val parsed = runCatching { CsvParser.parse(csv) }
        val validated = runCatching { WorkerResponseValidator.validateCsvTable(csv) }
        assertEquals("Parser/IPC acceptance differs for ${csv.toByteArray().contentToString()}",
            parsed.isSuccess, validated.isSuccess)
        if (valid != null) assertEquals(valid, validated.isSuccess)
        if (validated.isSuccess) assertEquals(csv, validated.getOrThrow())
    }

    @Test fun exhaustiveShortCsvMatchesTheIntegratedHostParser() {
        val alphabet = charArrayOf('a', ' ', ',', '"', '\r', '\n')
        fun visit(prefix: String, remaining: Int) {
            assertParity(prefix)
            if (remaining > 0) alphabet.forEach { visit(prefix + it, remaining - 1) }
        }
        visit("", 6)
    }

    @Test fun decodedQuotasAndMultilineGrammarMatch() {
        listOf("\"a\r\nb\",c\r\n", "\"a\"\"b\",\"x,y\"", "a,,", "\n\n", " ", "")
            .forEach { assertParity(it, true) }
        listOf("\"unterminated", "\"closed\"x", "bare\"quote", "\"closed\" \n")
            .forEach { assertParity(it, false) }
        listOf(1024 to true, 1025 to false).forEach { (n, ok) -> assertParity("\"" + "\"\"".repeat(n) + "\"", ok) }
        listOf(5000 to true, 5001 to false).forEach { (n, ok) -> assertParity("\n".repeat(n), ok) }
        listOf(128 to true, 129 to false).forEach { (n, ok) -> assertParity(List(n) { "" }.joinToString(","), ok) }
        assertParity(List(5000) { "a,b,c,d" }.joinToString("\n"), true)
        assertParity(List(5000) { "a,b,c,d" }.joinToString("\n") + ",e", false)
    }
}
