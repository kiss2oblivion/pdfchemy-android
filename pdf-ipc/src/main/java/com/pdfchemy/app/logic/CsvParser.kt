package com.pdfchemy.app.logic

/**
 * High-performance, RFC 4180 compliant CSV parser and validator.
 * Preserves quoted whitespace, escaped quotes, embedded newlines, empty cells, and blank rows.
 * Rejects malformed quotes (unclosed quotes, unexpected characters after closing quote, unescaped quotes in unquoted fields)
 * and enforces fail-closed resource limits.
 */
object CsvParser {

    const val MAX_FILE_RESULT_BYTES = 2 * 1024 * 1024L
    const val MAX_ROWS = 5000
    const val MAX_COLS = 128
    const val MAX_CELL_CHARS = 1024
    const val MAX_TOTAL_CELLS = 20000

    /**
     * Parses an RFC 4180 CSV string into a list of row lists.
     * Throws [IllegalArgumentException] on malformed CSV or exceeded quotas.
     *
     * Invariants:
     * 1. Preserves whitespace within quoted cells exactly as authored.
     * 2. Preserves escaped double quotes ("" -> ").
     * 3. Preserves embedded newlines (\r, \n, \r\n) inside quoted fields.
     * 4. Preserves empty cells (e.g. "a,,c" -> ["a", "", "c"]).
     * 5. Preserves blank rows without dropping them.
     * 6. Standard trailing newline at EOF does not produce an extraneous empty row.
     * 7. Rejects malformed quotations and unclosed quotes fail-closed.
     */
    fun parse(csv: String): List<List<String>> {
        if (csv.isEmpty()) return emptyList()
        require(csv.length <= MAX_FILE_RESULT_BYTES) { "CSV text exceeds maximum length of $MAX_FILE_RESULT_BYTES bytes" }

        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentCell = StringBuilder()
        var totalCells = 0

        var inQuotes = false
        var cellHasStarted = false
        var cellIsQuoted = false
        var quoteClosed = false
        var i = 0
        val len = csv.length

        fun commitCell() {
            require(currentRow.size + 1 <= MAX_COLS) { "CSV column limit exceeded (max $MAX_COLS)" }
            require(totalCells + 1 <= MAX_TOTAL_CELLS) { "CSV total cells limit exceeded (max $MAX_TOTAL_CELLS)" }
            require(currentCell.length <= MAX_CELL_CHARS) { "CSV cell character limit exceeded (max $MAX_CELL_CHARS)" }
            currentRow.add(currentCell.toString())
            totalCells++
            currentCell.clear()
            cellHasStarted = false
            cellIsQuoted = false
            quoteClosed = false
        }

        fun commitRow() {
            require(rows.size + 1 <= MAX_ROWS) { "CSV row limit exceeded (max $MAX_ROWS)" }
            rows.add(currentRow.toList())
            currentRow.clear()
        }

        while (i < len) {
            val ch = csv[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && csv[i + 1] == '"') {
                        // Escaped double quote inside quoted field: "" -> "
                        require(currentCell.length + 1 <= MAX_CELL_CHARS) { "CSV cell character limit exceeded (max $MAX_CELL_CHARS)" }
                        currentCell.append('"')
                        i += 2
                    } else {
                        // Closing double quote
                        inQuotes = false
                        quoteClosed = true
                        i++
                    }
                } else {
                    require(currentCell.length + 1 <= MAX_CELL_CHARS) { "CSV cell character limit exceeded (max $MAX_CELL_CHARS)" }
                    currentCell.append(ch)
                    i++
                }
            } else if (quoteClosed) {
                when (ch) {
                    ',' -> {
                        commitCell()
                        i++
                    }
                    '\r' -> {
                        if (i + 1 < len && csv[i + 1] == '\n') {
                            i++
                        }
                        commitCell()
                        commitRow()
                        i++
                    }
                    '\n' -> {
                        commitCell()
                        commitRow()
                        i++
                    }
                    else -> {
                        throw IllegalArgumentException("Malformed CSV: unexpected character after closing quote at index $i: '$ch'")
                    }
                }
            } else {
                when (ch) {
                    '"' -> {
                        if (!cellHasStarted) {
                            inQuotes = true
                            cellIsQuoted = true
                            cellHasStarted = true
                            i++
                        } else {
                            throw IllegalArgumentException("Malformed CSV: unescaped quote inside unquoted field at index $i")
                        }
                    }
                    ',' -> {
                        commitCell()
                        cellHasStarted = false
                        i++
                    }
                    '\r' -> {
                        if (i + 1 < len && csv[i + 1] == '\n') {
                            i++
                        }
                        commitCell()
                        commitRow()
                        i++
                    }
                    '\n' -> {
                        commitCell()
                        commitRow()
                        i++
                    }
                    else -> {
                        cellHasStarted = true
                        require(currentCell.length + 1 <= MAX_CELL_CHARS) { "CSV cell character limit exceeded (max $MAX_CELL_CHARS)" }
                        currentCell.append(ch)
                        i++
                    }
                }
            }
        }

        require(!inQuotes) { "Malformed CSV: unclosed quote at end of input" }

        if (cellHasStarted || quoteClosed || currentRow.isNotEmpty()) {
            commitCell()
            commitRow()
        }

        return rows
    }
}
