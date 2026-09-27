package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

object TextToPdfConverter {

    private const val MARGIN = 50f
    private const val FONT_SIZE = 12f
    private const val LEADING = 1.2f * FONT_SIZE

    /**
     * Converts plain text to a PDF document.
     * Supports line wrapping and multiple pages.
     */
    suspend fun convert(
        context: Context,
        text: String,
        destUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.failure(Exception("Text cannot be empty."))
        }

        var document: PDDocument? = null
        var outputStream: OutputStream? = null

        try {
            document = PDDocument()
            val font = PDType1Font.HELVETICA
            val mediaBox = PDRectangle.A4
            val width = mediaBox.width - 2 * MARGIN
            val startX = MARGIN
            val startY = mediaBox.height - MARGIN

            var currentPage = PDPage(mediaBox)
            document.addPage(currentPage)
            var contentStream = PDPageContentStream(document, currentPage)
            
            contentStream.beginText()
            contentStream.setFont(font, FONT_SIZE)
            contentStream.newLineAtOffset(startX, startY)

            var yOffset = startY
            
            // Split text into lines and wrap them
            val lines = text.split("\n")
            for (line in lines) {
                val cleanLine = line.replace("\r", "")
                val wrappedLines = wrapText(cleanLine, font, FONT_SIZE, width)
                for (wrappedLine in wrappedLines) {
                    if (yOffset - LEADING < MARGIN) {
                        // Page break
                        contentStream.endText()
                        contentStream.close()
                        
                        currentPage = PDPage(mediaBox)
                        document.addPage(currentPage)
                        contentStream = PDPageContentStream(document, currentPage)
                        contentStream.beginText()
                        contentStream.setFont(font, FONT_SIZE)
                        contentStream.newLineAtOffset(startX, startY)
                        yOffset = startY
                    }
                    
                    // PDFBox Type1 fonts don't support all Unicode chars.
                    // Sanitize before showText to prevent IllegalArgumentException.
                    val sanitized = wrappedLine.filter { it.code in 32..126 || it.code in 160..255 }
                    contentStream.showText(sanitized)
                    contentStream.newLineAtOffset(0f, -LEADING)
                    yOffset -= LEADING
                }
            }

            contentStream.endText()
            contentStream.close()

            outputStream = context.contentResolver.openOutputStream(destUri)
                ?: return@withContext Result.failure(Exception("Failed to open destination for saving."))
            
            document.save(outputStream)
            Result.success(Unit)

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
            outputStream?.close()
        }
    }

    private fun wrapText(text: String, font: PDType1Font, fontSize: Float, width: Float): List<String> {
        val wrappedLines = mutableListOf<String>()
        var currentLine = StringBuilder()
        val words = text.split(" ")

        for (word in words) {
            val prospectiveLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            // Sanitize for PDType1Font compatibility before measuring
            val sanitizedProspective = prospectiveLine.filter { it.code in 32..126 || it.code in 160..255 }
            val lineWidth = try {
                fontSize * font.getStringWidth(sanitizedProspective) / 1000
            } catch (e: Exception) {
                // Fallback: estimate width based on character count
                sanitizedProspective.length * fontSize * 0.6f
            }
            if (lineWidth > width) {
                if (currentLine.isNotEmpty()) {
                    wrappedLines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    // Word itself is longer than width, force wrap it (rare)
                    wrappedLines.add(word)
                    currentLine = StringBuilder()
                }
            } else {
                currentLine = StringBuilder(prospectiveLine)
            }
        }
        if (currentLine.isNotEmpty()) {
            wrappedLines.add(currentLine.toString())
        }
        return if (wrappedLines.isEmpty() && text.isNotEmpty()) listOf("") else wrappedLines
    }
}
