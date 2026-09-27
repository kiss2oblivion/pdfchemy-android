package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.max

object MarkdownEngine {

    /**
     * Converts raw Markdown text into a styled vector PDF document.
     */
    suspend fun markdownToPdf(
        context: Context,
        markdownText: String,
        destPdfUri: Uri,
        documentTitle: String = "Document",
        pageSize: PDRectangle = PDRectangle.A4
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val doc = PDDocument()
        var document: PDDocument? = doc
        var tempFile: File? = null

        try {
            val pageWidth = pageSize.width
            val pageHeight = pageSize.height
            val margin = 50f
            val contentWidth = pageWidth - (margin * 2)

            var currentPage = PDPage(pageSize)
            doc.addPage(currentPage)
            var contentStream = PDPageContentStream(doc, currentPage)
            var currentY = pageHeight - margin

            fun startNewPage() {
                contentStream.close()
                currentPage = PDPage(pageSize)
                doc.addPage(currentPage)
                contentStream = PDPageContentStream(doc, currentPage)
                currentY = pageHeight - margin
            }

            // Draw Document Header if provided
            if (documentTitle.isNotBlank()) {
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                contentStream.newLineAtOffset(margin, currentY)
                contentStream.showText(sanitizeText(documentTitle))
                contentStream.endText()
                currentY -= 28f

                // Header separator line
                contentStream.setStrokingColor(200, 200, 200)
                contentStream.setLineWidth(1f)
                contentStream.moveTo(margin, currentY + 10f)
                contentStream.lineTo(pageWidth - margin, currentY + 10f)
                contentStream.stroke()
                currentY -= 15f
            }

            val lines = markdownText.lines()
            var inCodeBlock = false
            val codeLines = mutableListOf<String>()

            for (rawLine in lines) {
                val trimmed = rawLine.trim()

                // Code block fence ```
                if (trimmed.startsWith("```")) {
                    if (inCodeBlock) {
                        // End code block
                        inCodeBlock = false
                        val blockHeight = (codeLines.size * 14f) + 12f
                        if (currentY - blockHeight < margin) {
                            startNewPage()
                        }

                        // Draw background box for code
                        contentStream.setNonStrokingColor(240, 242, 245)
                        contentStream.addRect(margin, currentY - blockHeight + 10f, contentWidth, blockHeight)
                        contentStream.fill()

                        // Draw code border
                        contentStream.setStrokingColor(210, 215, 220)
                        contentStream.setLineWidth(0.8f)
                        contentStream.addRect(margin, currentY - blockHeight + 10f, contentWidth, blockHeight)
                        contentStream.stroke()

                        // Draw code text
                        var codeY = currentY
                        for (cLine in codeLines) {
                            contentStream.beginText()
                            contentStream.setNonStrokingColor(40, 45, 50)
                            contentStream.setFont(PDType1Font.COURIER, 9.5f)
                            contentStream.newLineAtOffset(margin + 8f, codeY)
                            contentStream.showText(sanitizeText(cLine.take(90)))
                            contentStream.endText()
                            codeY -= 14f
                        }
                        currentY -= (blockHeight + 12f)
                        codeLines.clear()
                    } else {
                        inCodeBlock = true
                        codeLines.clear()
                    }
                    continue
                }

                if (inCodeBlock) {
                    codeLines.add(rawLine)
                    continue
                }

                // Empty line -> spacing
                if (trimmed.isEmpty()) {
                    currentY -= 10f
                    if (currentY < margin + 20f) startNewPage()
                    continue
                }

                // Horizontal Rule ---
                if (trimmed.matches(Regex("^-{3,}|_{3,}|\\*{3,}$"))) {
                    if (currentY < margin + 20f) startNewPage()
                    contentStream.setStrokingColor(220, 220, 220)
                    contentStream.setLineWidth(0.8f)
                    contentStream.moveTo(margin, currentY)
                    contentStream.lineTo(pageWidth - margin, currentY)
                    contentStream.stroke()
                    currentY -= 15f
                    continue
                }

                // Headings
                when {
                    trimmed.startsWith("# ") -> {
                        val text = trimmed.removePrefix("# ").trim()
                        if (currentY < margin + 40f) startNewPage()
                        currentY -= 10f
                        contentStream.beginText()
                        contentStream.setNonStrokingColor(25, 30, 40)
                        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                        contentStream.newLineAtOffset(margin, currentY)
                        contentStream.showText(sanitizeText(text))
                        contentStream.endText()
                        currentY -= 24f
                    }
                    trimmed.startsWith("## ") -> {
                        val text = trimmed.removePrefix("## ").trim()
                        if (currentY < margin + 30f) startNewPage()
                        currentY -= 8f
                        contentStream.beginText()
                        contentStream.setNonStrokingColor(40, 50, 65)
                        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 13.5f)
                        contentStream.newLineAtOffset(margin, currentY)
                        contentStream.showText(sanitizeText(text))
                        contentStream.endText()
                        currentY -= 20f
                    }
                    trimmed.startsWith("### ") -> {
                        val text = trimmed.removePrefix("### ").trim()
                        if (currentY < margin + 25f) startNewPage()
                        contentStream.beginText()
                        contentStream.setNonStrokingColor(60, 70, 85)
                        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 11.5f)
                        contentStream.newLineAtOffset(margin, currentY)
                        contentStream.showText(sanitizeText(text))
                        contentStream.endText()
                        currentY -= 16f
                    }
                    // Blockquote >
                    trimmed.startsWith(">") -> {
                        val text = trimmed.removePrefix(">").trim()
                        if (currentY < margin + 20f) startNewPage()

                        contentStream.setStrokingColor(70, 130, 220)
                        contentStream.setLineWidth(3f)
                        contentStream.moveTo(margin, currentY + 8f)
                        contentStream.lineTo(margin, currentY - 6f)
                        contentStream.stroke()

                        contentStream.beginText()
                        contentStream.setNonStrokingColor(90, 95, 105)
                        contentStream.setFont(PDType1Font.HELVETICA_OBLIQUE, 10f)
                        contentStream.newLineAtOffset(margin + 12f, currentY)
                        contentStream.showText(sanitizeText(text))
                        contentStream.endText()
                        currentY -= 16f
                    }
                    // Bullet list (- or *)
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        val text = trimmed.substring(2).trim()
                        if (currentY < margin + 18f) startNewPage()

                        contentStream.beginText()
                        contentStream.setNonStrokingColor(30, 30, 30)
                        contentStream.setFont(PDType1Font.HELVETICA, 10f)
                        contentStream.newLineAtOffset(margin + 10f, currentY)
                        contentStream.showText("• " + sanitizeText(text))
                        contentStream.endText()
                        currentY -= 15f
                    }
                    // Numbered list
                    trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                        if (currentY < margin + 18f) startNewPage()

                        contentStream.beginText()
                        contentStream.setNonStrokingColor(30, 30, 30)
                        contentStream.setFont(PDType1Font.HELVETICA, 10f)
                        contentStream.newLineAtOffset(margin + 10f, currentY)
                        contentStream.showText(sanitizeText(trimmed))
                        contentStream.endText()
                        currentY -= 15f
                    }
                    // Standard Paragraph
                    else -> {
                        val wrapped = wrapText(trimmed, 80)
                        for (wLine in wrapped) {
                            if (currentY < margin + 18f) startNewPage()
                            contentStream.beginText()
                            contentStream.setNonStrokingColor(30, 30, 30)
                            contentStream.setFont(PDType1Font.HELVETICA, 10f)
                            contentStream.newLineAtOffset(margin, currentY)
                            contentStream.showText(sanitizeText(wLine))
                            contentStream.endText()
                            currentY -= 14f
                        }
                    }
                }
            }

            contentStream.close()

            // Save to temp file
            tempFile = File(context.cacheDir, "md_temp_${System.currentTimeMillis()}.pdf")
            doc.save(tempFile)
            doc.close()
            document = null

            context.contentResolver.openOutputStream(destPdfUri)?.use { out ->
                tempFile.inputStream().use { inp ->
                    inp.copyTo(out)
                }
            } ?: throw IllegalStateException("Cannot open output stream")

            tempFile.delete()
            tempFile = null

            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(
                destPdfUri,
                FileUtils.getFileName(context, destPdfUri) ?: "document.pdf",
                "Markdown to PDF"
            )

            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("MarkdownEngine: Error converting markdown to PDF", e)
            Result.failure(e)
        } finally {
            try { document?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    /**
     * Extracts text from a PDF and converts it into structured Markdown format.
     */
    suspend fun pdfToMarkdown(
        context: Context,
        pdfUri: Uri
    ): Result<String> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        var inputStream: InputStream? = null
        var document: PDDocument? = null

        try {
            inputStream = context.contentResolver.openInputStream(pdfUri)
                ?: throw IllegalStateException("Cannot open input PDF")

            document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            val rawText = stripper.getText(document)

            val mdBuilder = StringBuilder()
            val lines = rawText.lines()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    mdBuilder.append("\n")
                    continue
                }

                // Heuristic: short line in all caps or title case -> heading
                if (trimmed.length in 3..45 && (trimmed == trimmed.uppercase() || !trimmed.endsWith("."))) {
                    mdBuilder.append("## ").append(trimmed).append("\n\n")
                } else if (trimmed.startsWith("•") || trimmed.startsWith("-")) {
                    mdBuilder.append("- ").append(trimmed.removePrefix("•").removePrefix("-").trim()).append("\n")
                } else if (trimmed.matches(Regex("^\\d+\\.\\s.*"))) {
                    mdBuilder.append(trimmed).append("\n")
                } else {
                    mdBuilder.append(trimmed).append("\n\n")
                }
            }

            Result.success(mdBuilder.toString().trim())
        } catch (e: Exception) {
            AppLogger.e("MarkdownEngine: Error extracting Markdown from PDF", e)
            Result.failure(e)
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun sanitizeText(input: String): String {
        return input.replace("\t", "    ")
            .filter { it.code in 32..126 || it == '\n' || it == '\r' }
    }

    private fun wrapText(text: String, maxCharsPerLine: Int): List<String> {
        if (text.length <= maxCharsPerLine) return listOf(text)
        val words = text.split(" ")
        val result = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.length + word.length + 1 > maxCharsPerLine) {
                if (currentLine.isNotEmpty()) {
                    result.add(currentLine.toString())
                    currentLine = StringBuilder()
                }
            }
            if (currentLine.isNotEmpty()) currentLine.append(" ")
            currentLine.append(word)
        }
        if (currentLine.isNotEmpty()) result.add(currentLine.toString())
        return result
    }
}
