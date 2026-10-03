package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.*

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.jail.AppLogger
import com.pdfchemy.app.utils.FileUtils
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

object PdfHeaderFooterEngine {

    /**
     * Stamps dynamic headers, footers, or legal Bates numbers onto PDF pages.
     */
    suspend fun applyHeaderFooter(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        config: StampConfig
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        var inputStream: InputStream? = null
        var document: PDDocument? = null
        var tempFile: File? = null

        try {
            inputStream = com.pdfchemy.app.jail.CapabilityIo.input(context, sourcePdfUri)
                ?: throw IllegalStateException("Cannot open input PDF")

            document = PDDocument.load(inputStream, com.pdfchemy.app.jail.JailMemory.settings())
            val totalPages = document.numberOfPages
            if (totalPages == 0) {
                return@withContext Result.failure(IllegalStateException("PDF contains no pages"))
            }

            val fileName = FileUtils.getFileName(context, sourcePdfUri) ?: "Document"
            val title = document.documentInformation?.title ?: fileName
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            val font = PDType1Font.HELVETICA
            val fontSize = config.fontSize

            for (i in 0 until totalPages) {
                val pageNum = i + 1
                if (pageNum < config.startFromPage) continue

                val page = document.getPage(i)
                val mediaBox = page.mediaBox
                val pageWidth = mediaBox.width
                val pageHeight = mediaBox.height

                // Expand macros
                var text = if (config.batesConfig.enabled) {
                    val batesNum = config.batesConfig.startNumber + (pageNum - config.startFromPage)
                    val formattedNum = "%0${config.batesConfig.digits}d".format(batesNum)
                    "${config.batesConfig.prefix}$formattedNum${config.batesConfig.suffix}"
                } else {
                    config.templateText
                        .replace("{page}", pageNum.toString())
                        .replace("{total}", totalPages.toString())
                        .replace("{date}", dateStr)
                        .replace("{filename}", fileName)
                        .replace("{title}", title)
                }

                text = text.filter { it.code in 32..126 } // ASCII safe
                if (text.isBlank()) continue

                val textWidth = (font.getStringWidth(text) / 1000f) * fontSize

                // Calculate X and Y based on position
                val (x, y) = when (config.position) {
                    HeaderFooterPosition.HEADER_LEFT -> {
                        config.marginPt to (pageHeight - config.marginPt)
                    }
                    HeaderFooterPosition.HEADER_CENTER -> {
                        ((pageWidth - textWidth) / 2f) to (pageHeight - config.marginPt)
                    }
                    HeaderFooterPosition.HEADER_RIGHT -> {
                        (pageWidth - config.marginPt - textWidth) to (pageHeight - config.marginPt)
                    }
                    HeaderFooterPosition.FOOTER_LEFT -> {
                        config.marginPt to config.marginPt
                    }
                    HeaderFooterPosition.FOOTER_CENTER -> {
                        ((pageWidth - textWidth) / 2f) to config.marginPt
                    }
                    HeaderFooterPosition.FOOTER_RIGHT -> {
                        (pageWidth - config.marginPt - textWidth) to config.marginPt
                    }
                }

                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setNonStrokingColor(70, 75, 85)
                    cs.newLineAtOffset(x, y)
                    cs.showText(text)
                    cs.endText()
                }
            }

            tempFile = com.pdfchemy.app.jail.JailScratch.namedFile("stamp_${System.currentTimeMillis()}.pdf")
            com.pdfchemy.app.jail.boundedFileOutput(tempFile).use { document.save(it) }
            document.close()
            document = null

            com.pdfchemy.app.jail.CapabilityIo.output(context, destPdfUri)?.let { com.pdfchemy.app.security.BoundedOutputStream(it) }?.use { out ->
                com.pdfchemy.app.jail.CapabilityIo.input(tempFile).use { inp ->
                    inp.copyTo(out)
                }
            } ?: throw IllegalStateException("Cannot open destination stream")

            tempFile.delete()
            tempFile = null


            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfHeaderFooterEngine: Error stamping document", e)
            Result.failure(e)
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }
}
