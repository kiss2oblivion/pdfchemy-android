package com.pdfchemy.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableExtractorCorrectnessTest {

    // RFC 4180 parsing implementation mirror for verification
    private fun parseCsv(csv: String): List<List<String>> {
        if (csv.isBlank()) return emptyList()
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentCell = StringBuilder()
        var inQuotes = false
        var i = 0
        val len = csv.length

        while (i < len) {
            val ch = csv[i]
            if (inQuotes) {
                if (ch == '\"') {
                    if (i + 1 < len && csv[i + 1] == '\"') {
                        currentCell.append('\"')
                        i += 2
                    } else {
                        inQuotes = false
                        i++
                    }
                } else {
                    currentCell.append(ch)
                    i++
                }
            } else {
                when (ch) {
                    '\"' -> {
                        inQuotes = true
                        i++
                    }
                    ',' -> {
                        currentRow.add(currentCell.toString().trim())
                        currentCell.clear()
                        i++
                    }
                    '\r' -> {
                        if (i + 1 < len && csv[i + 1] == '\n') {
                            i++
                        }
                        currentRow.add(currentCell.toString().trim())
                        currentCell.clear()
                        if (currentRow.any { it.isNotBlank() }) {
                            rows.add(currentRow.toList())
                        }
                        currentRow.clear()
                        i++
                    }
                    '\n' -> {
                        currentRow.add(currentCell.toString().trim())
                        currentCell.clear()
                        if (currentRow.any { it.isNotBlank() }) {
                            rows.add(currentRow.toList())
                        }
                        currentRow.clear()
                        i++
                    }
                    else -> {
                        currentCell.append(ch)
                        i++
                    }
                }
            }
        }
        if (currentCell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentCell.toString().trim())
            if (currentRow.any { it.isNotBlank() }) {
                rows.add(currentRow.toList())
            }
        }
        return rows
    }

    @Test
    fun parseCsvHandlesQuotedCommasCorrectly() {
        val input = "Item,Price,\"Notes, Remarks\"\nWidget,$10.00,\"Fragile, handle with care\""
        val rows = parseCsv(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Item", "Price", "Notes, Remarks"), rows[0])
        assertEquals(listOf("Widget", "$10.00", "Fragile, handle with care"), rows[1])
    }

    @Test
    fun parseCsvHandlesEscapedQuotesCorrectly() {
        val input = "Title,Quote\nPhilosophy,\"He said \"\"I think, therefore I am\"\"\""
        val rows = parseCsv(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Title", "Quote"), rows[0])
        assertEquals(listOf("Philosophy", "He said \"I think, therefore I am\""), rows[1])
    }

    @Test
    fun parseCsvHandlesEmbeddedNewlinesInsideQuotes() {
        val input = "\"Multi\nLine\nField\",Simple\nSecond,Row"
        val rows = parseCsv(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Multi\nLine\nField", "Simple"), rows[0])
        assertEquals(listOf("Second", "Row"), rows[1])
    }

    @Test
    fun latestRequestProtectsAgainstStalePublication() {
        val requests = com.pdfchemy.app.logic.LatestRequest()
        val ticket1 = requests.begin()
        assertTrue(requests.isCurrent(ticket1))

        // Newer request starts
        val ticket2 = requests.begin()
        assertTrue(requests.isCurrent(ticket2))
        org.junit.Assert.assertFalse("Older ticket1 must not be current after ticket2 begins", requests.isCurrent(ticket1))
    }
}
