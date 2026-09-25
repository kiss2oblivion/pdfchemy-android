package com.pdfchemy.desktop.jail

import com.pdfchemy.desktop.engine.*

import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.Word
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.io.File
import java.io.InputStream
import java.nio.file.Files

object PdfOcrEngine {

    /**
     * Extracts tessdata from resources to a temporary folder to use with Tesseract.
     */
    private fun extractTessData(): File {
        val tmpDir = File(com.pdfchemy.desktop.engine.DesktopStaging.stagingDir, "pdfchemy_tessdata")
        if (!tmpDir.exists()) {
            tmpDir.mkdirs()
        }
        val targetFile = File(tmpDir, "eng.traineddata")
        if (!targetFile.exists()) {
            val resourceStream: InputStream? = javaClass.getResourceAsStream("/tessdata/eng.traineddata")
            if (resourceStream != null) {
                Files.copy(resourceStream, targetFile.toPath())
            } else {
                throw RuntimeException("eng.traineddata not found in resources")
            }
        }
        return tmpDir
    }

    /**
     * Performs offline OCR recognition on a PDF document and generates a searchable PDF
     * by injecting an invisible, selectable text layer over the recognized words.
     */
    fun createSearchablePdf(
        inputFile: File,
        outputFile: File,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Boolean {
        var inputDoc: PDDocument? = null
        var outputDoc: PDDocument? = null

        try {
            val tessDataPath = extractTessData()
            val tesseract = Tesseract()
            tesseract.setDatapath(tessDataPath.absolutePath)
            tesseract.setLanguage("eng")

            inputDoc = PDDocument.load(inputFile, MemoryUsageSetting.setupTempFileOnly())
            val pdfRenderer = PDFRenderer(inputDoc!!)
            val pageCount = inputDoc!!.numberOfPages
            
            if (pageCount == 0) return false
            outputDoc = PDDocument()

            for (i in 0 until pageCount) {
                onProgress(i + 1, pageCount)
                
                // Render at 300 DPI for good OCR accuracy
                val dpi = 300f
                val scale = dpi / 72f
                val originalPage = inputDoc!!.getPage(i)
                val pageWidth = originalPage.mediaBox.width
                val pageHeight = originalPage.mediaBox.height
                
                val image = pdfRenderer.renderImageWithDPI(i, dpi)

                // Create PDF page with matching dimensions
                val pdPage = PDPage(PDRectangle(pageWidth, pageHeight))
                outputDoc.addPage(pdPage)

                // 1. Draw original page image
                val pdImage = LosslessFactory.createFromImage(outputDoc, image)
                val contentStream = PDPageContentStream(outputDoc, pdPage)
                contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)

                // 2. OCR and inject transparent text layer
                val words: List<Word> = tesseract.getWords(image, 3) // ITesseract.PAGE_ITERATOR_LEVEL_WORD = 3
                
                if (words.isNotEmpty()) {
                    val extGState = PDExtendedGraphicsState().apply {
                        nonStrokingAlphaConstant = 0.0f
                    }
                    contentStream.setGraphicsStateParameters(extGState)

                    val bmpWidth = image.width.toFloat()
                    val bmpHeight = image.height.toFloat()

                    for (word in words) {
                        val text = word.text.trim()
                        if (text.isEmpty()) continue

                        val box = word.boundingBox
                        
                        val scaleX = pageWidth / bmpWidth
                        val scaleY = pageHeight / bmpHeight

                        val x = box.x * scaleX
                        val y = pageHeight - ((box.y + box.height) * scaleY) // In PDF, y=0 is bottom
                        val elementHeight = box.height * scaleY

                        val fontSize = elementHeight.coerceIn(4f, 72f)
                        val font = PDType1Font.HELVETICA

                        try {
                            contentStream.beginText()
                            contentStream.setFont(font, fontSize)
                            contentStream.newLineAtOffset(x, y)
                            contentStream.showText(text)
                            contentStream.endText()
                        } catch (e: Exception) {
                            // Ignore unsupported glyphs
                        }
                    }
                }

                contentStream.close()
            }

            outputDoc.save(outputFile)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            inputDoc?.close()
            outputDoc?.close()
        }
    }
}
