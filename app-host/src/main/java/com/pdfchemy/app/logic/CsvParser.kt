package com.pdfchemy.app.logic

/**
 * High-performance, RFC 4180 compliant CSV parser.
 * Preserves quoted whitespace, escaped quotes, embedded newlines, empty cells, and blank rows.
 */
object CsvParser {

    /**
     * Parses an RFC 4180 CSV string into a list of row lists.
     *
     * Invariants:
     * 1. Preserves whitespace within quoted cells exactly as authored.
     * 2. Preserves escaped double quotes ("" -> ").
     * 3. Preserves embedded newlines (\r, \n, \r\n) inside quoted fields.
     * 4. Preserves empty cells (e.g. "a,,c" -> ["a", "", "c"]).
     * 5. Preserves blank rows without dropping them.
     * 6. Standard trailing newline at EOF does not produce an extraneous empty row.
     */
    fun parse(csv: String): List<List<String>> {
        if (csv.isEmpty()) return emptyList()

        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentCell = StringBuilder()
        var inQuotes = false
        var cellIsQuoted = false
        var i = 0
        val len = csv.length

        while (i < len) {
            val ch = csv[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && csv[i + 1] == '"') {
                        // Escaped double quote inside quoted field: "" -> "
                        currentCell.append('"')
                        i += 2
                    } else {
                        // Closing double quote
                        inQuotes = false
                        i++
                    }
                } else {
                    currentCell.append(ch)
                    i++
                }
            } else {
                when (ch) {
                    '"' -> {
                        inQuotes = true
                        cellIsQuoted = true
                        i++
                    }
                    ',' -> {
                        currentRow.add(currentCell.toString())
                        currentCell.clear()
                        cellIsQuoted = false
                        i++
                    }
                    '\r' -> {
                        if (i + 1 < len && csv[i + 1] == '\n') {
                            i++ // Skip \r in \r\n sequence
                        }
                        currentRow.add(currentCell.toString())
                        currentCell.clear()
                        cellIsQuoted = false
                        rows.add(currentRow.toList())
                        currentRow.clear()
                        i++
                    }
                    '\n' -> {
                        currentRow.add(currentCell.toString())
                        currentCell.clear()
                        cellIsQuoted = false
                        rows.add(currentRow.toList())
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

        // Add trailing cell/row if uncommitted data or an explicit quoted empty cell remains
        if (currentCell.isNotEmpty() || currentRow.isNotEmpty() || cellIsQuoted) {
            currentRow.add(currentCell.toString())
            rows.add(currentRow.toList())
        }

        return rows
    }
}
