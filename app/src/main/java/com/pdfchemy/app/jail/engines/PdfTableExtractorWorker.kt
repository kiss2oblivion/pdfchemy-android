package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.File

object PdfTableExtractorWorker {

    private data class SpatialWord(
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    fun extractText(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor?, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val safeMode = params.optBoolean("safeMode", false)
        val pageIndex = if (params.has("pageIndex") && !params.isNull("pageIndex")) params.getInt("pageIndex") else null

        var document: PDDocument? = null
        var tempFile: File? = null
        try {
            tempFile = File.createTempFile("table_extract", ".pdf")
            FileOutputStream(tempFile).use { out ->
                FileInputStream(sourceFd.fileDescriptor).use { inp ->
                    inp.copyTo(out)
                }
            }

            document = PDDocument.load(tempFile, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val csv = extractFromDocument(document, pageIndex, safeMode)

            if (targetFd != null) {
                FileOutputStream(targetFd.fileDescriptor).use { out ->
                    OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                        writer.write(csv)
                    }
                }
            }

            val result = JSONObject()
            result.put("success", csv.isNotBlank())
            return result.toString()
        } finally {
            try { document?.close() } catch (e: Exception) {}
            tempFile?.delete()
        }
    }

    private fun extractFromDocument(doc: PDDocument, pageIndex: Int? = null, safeMode: Boolean = false): String {
        val totalPages = doc.numberOfPages
        if (totalPages == 0) return ""

        val startPage = if (pageIndex != null) (pageIndex + 1).coerceIn(1, totalPages) else 1
        val endPage = if (pageIndex != null) (pageIndex + 1).coerceIn(1, totalPages) else totalPages

        val allRows = mutableListOf<List<String>>()

        for (p in startPage..endPage) {
            val pageRows = extractPageRows(doc, p)
            if (pageRows.isNotEmpty()) {
                allRows.addAll(pageRows)
            }
        }

        if (allRows.isEmpty()) return ""

        val sb = StringBuilder()
        for (row in allRows) {
            val rowStr = row.joinToString(",") { cell ->
                var finalCell = cell
                
                if (safeMode && finalCell.isNotEmpty()) {
                    val firstChar = finalCell[0]
                    if (firstChar == '=' || firstChar == '+' || firstChar == '-' || firstChar == '@' || firstChar == '\t' || firstChar == '\r' || firstChar == '\u0000') {
                        finalCell = "'$finalCell"
                    }
                }

                val escaped = finalCell.replace("\"", "\"\"")
                if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
                    "\"$escaped\""
                } else {
                    escaped
                }
            }
            sb.append(rowStr).append("\r\n")
        }

        return sb.toString()
    }

    private fun extractPageRows(doc: PDDocument, pageNumber1Based: Int): List<List<String>> {
        val allWords = mutableListOf<SpatialWord>()
        val currentWordChars = StringBuilder()
        var wordStartX = 0f
        var wordStartY = 0f
        var wordEndX = 0f
        var wordMaxHeight = 0f
        var inWord = false

        fun flushWord() {
            if (inWord && currentWordChars.isNotEmpty()) {
                val str = currentWordChars.toString().trim()
                if (str.isNotEmpty()) {
                    allWords.add(
                        SpatialWord(
                            text = str,
                            x = wordStartX,
                            y = wordStartY,
                            width = (wordEndX - wordStartX).coerceAtLeast(1f),
                            height = wordMaxHeight
                        )
                    )
                }
                currentWordChars.setLength(0)
                inWord = false
            }
        }

        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                val str = text.unicode
                val isSpace = str.isBlank()

                if (isSpace) {
                    flushWord()
                } else {
                    val x = text.xDirAdj
                    val y = text.yDirAdj
                    val w = text.widthDirAdj
                    val h = text.heightDir

                    if (inWord) {
                        val gap = x - wordEndX
                        if (Math.abs(y - wordStartY) < 3.0f && gap >= -1.0f && gap <= (text.widthOfSpace * 1.5f).coerceAtLeast(3f)) {
                            currentWordChars.append(str)
                            wordEndX = x + w
                            wordMaxHeight = maxOf(wordMaxHeight, h)
                        } else {
                            flushWord()
                            currentWordChars.append(str)
                            wordStartX = x
                            wordStartY = y
                            wordEndX = x + w
                            wordMaxHeight = h
                            inWord = true
                        }
                    } else {
                        currentWordChars.append(str)
                        wordStartX = x
                        wordStartY = y
                        wordEndX = x + w
                        wordMaxHeight = h
                        inWord = true
                    }
                }
                super.processTextPosition(text)
            }
        }
        stripper.startPage = pageNumber1Based
        stripper.endPage = pageNumber1Based
        stripper.writeText(doc, java.io.StringWriter())
        flushWord()

        if (allWords.isEmpty()) return emptyList()

        val lineThreshold = 3.5f
        val sortedByY = allWords.sortedWith(compareBy({ it.y }, { it.x }))

        val lines = mutableListOf<MutableList<SpatialWord>>()
        var currentLine = mutableListOf<SpatialWord>()
        var currentY = -1f

        for (item in sortedByY) {
            if (currentY < 0f || Math.abs(item.y - currentY) <= lineThreshold) {
                currentLine.add(item)
                if (currentY < 0f) currentY = item.y
            } else {
                if (currentLine.isNotEmpty()) {
                    currentLine.sortBy { it.x }
                    lines.add(currentLine)
                }
                currentLine = mutableListOf(item)
                currentY = item.y
            }
        }
        if (currentLine.isNotEmpty()) {
            currentLine.sortBy { it.x }
            lines.add(currentLine)
        }

        val columnGapThreshold = 14.0f
        val csvRows = mutableListOf<List<String>>()

        for (line in lines) {
            val cells = mutableListOf<String>()
            val currentCellText = StringBuilder()
            var lastRight = -1f

            for (w in line) {
                if (lastRight >= 0f && (w.x - lastRight) > columnGapThreshold) {
                    cells.add(currentCellText.toString().trim())
                    currentCellText.clear()
                } else if (lastRight >= 0f && (w.x - lastRight) > 2.0f) {
                    currentCellText.append(" ")
                }
                currentCellText.append(w.text)
                lastRight = w.x + w.width
            }
            if (currentCellText.isNotEmpty()) {
                cells.add(currentCellText.toString().trim())
            }

            if (cells.isNotEmpty() && cells.any { it.isNotBlank() }) {
                csvRows.add(cells)
            }
        }

        return csvRows
    }
}
