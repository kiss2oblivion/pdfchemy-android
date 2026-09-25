package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.json.JSONObject
import java.io.FileOutputStream

object PdfOcrEngineWorker {

    fun createSearchablePdf(context: Context, sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var renderer: PdfRenderer? = null
        var outputDoc: PDDocument? = null

        try {
            renderer = PdfRenderer(sourceFd)
            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                return JSONObject().put("success", false).put("error", "No pages to OCR").toString()
            }

            outputDoc = PDDocument()

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                
                var bitmap: Bitmap? = null
                var contentStream: PDPageContentStream? = null
                try {
                    val pageWidth = page.width.toFloat()
                    val pageHeight = page.height.toFloat()

                    val maxDim = maxOf(pageWidth, pageHeight)
                    val renderScale = if (maxDim > 0f) minOf(2f, 2048f / maxDim) else 1f
                    val bmpWidth = (pageWidth * renderScale).toInt().coerceIn(1, 2048)
                    val bmpHeight = (pageHeight * renderScale).toInt().coerceIn(1, 2048)

                    bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val visionText: Text = try {
                        Tasks.await(recognizer.process(inputImage))
                    } catch (e: Exception) {
                        null
                    } ?: Tasks.await(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(inputImage))

                    val pdPage = PDPage(PDRectangle(pageWidth, pageHeight))
                    outputDoc.addPage(pdPage)

                    val pdImage = JPEGFactory.createFromImage(outputDoc, bitmap, 0.85f)
                    contentStream = PDPageContentStream(outputDoc, pdPage)
                    contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)

                    if (visionText.textBlocks.isNotEmpty()) {
                        val extGState = PDExtendedGraphicsState().apply {
                            nonStrokingAlphaConstant = 0.0f
                        }
                        contentStream.setGraphicsStateParameters(extGState)

                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                for (element in line.elements) {
                                    val box = element.boundingBox ?: continue
                                    val text = element.text.trim()
                                    if (text.isEmpty()) continue

                                    val scaleX = pageWidth / bmpWidth.toFloat()
                                    val scaleY = pageHeight / bmpHeight.toFloat()

                                    val x = box.left * scaleX
                                    val y = pageHeight - (box.bottom * scaleY)
                                    val elementHeight = box.height() * scaleY

                                    val fontSize = elementHeight.coerceIn(4f, 72f)
                                    val font = PDType1Font.HELVETICA

                                    try {
                                        contentStream.beginText()
                                        contentStream.setFont(font, fontSize)
                                        contentStream.newLineAtOffset(x, y)
                                        contentStream.showText(text)
                                        contentStream.endText()
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                } finally {
                    try { contentStream?.close() } catch (e: Exception) {}
                    bitmap?.recycle()
                    page.close()
                }
            }

            FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                outputDoc.save(outStream)
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { outputDoc?.close() } catch (e: Exception) {}
            try { renderer?.close() } catch (e: Exception) {}
            recognizer.close()
        }
    }
}
