package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.ocr.OcrBackendFactory
import com.pdfchemy.app.jail.ocr.OcrResultBounds
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.util.Matrix
import org.json.JSONObject
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream

object PdfOcrEngineWorker {

    fun createSearchablePdf(context: Context, sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor,
        onProgress: (Int, Int, Boolean) -> Unit = { _, _, _ -> }): String {
        val ocrBackend = OcrBackendFactory.create(context)
        var renderer: PdfRenderer? = null
        var outputDoc: PDDocument? = null
        var originalDoc: PDDocument? = null

        try {
            // PdfRenderer reports an integer, rotated crop plane, not the original
            // PDF coordinate system. Read geometry through the transferred FD.
            originalDoc = com.pdfchemy.app.jail.CapabilityIo.input(sourceFd.fileDescriptor).use {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            }
            android.system.Os.lseek(sourceFd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
            renderer = PdfRenderer(sourceFd)
            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                return JSONObject().put("success", false).put("error", "No pages to OCR").toString()
            }
            require(pageCount <= com.pdfchemy.app.security.SecurityLimits.MAX_OUTPUT_FILES) { "Page count quota exceeded" }
            require(originalDoc.numberOfPages == pageCount) { "Renderer page count mismatch" }

            onProgress(0, pageCount, false)
            outputDoc = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                
                var bitmap: Bitmap? = null
                var contentStream: PDPageContentStream? = null
                try {
                    val originalPage = originalDoc.getPage(i)
                    val media = originalPage.mediaBox
                    val crop = originalPage.cropBox ?: media
                    fun copyBox(box: PDRectangle): PDRectangle {
                        require(listOf(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY).all { it.isFinite() } &&
                            box.width.isFinite() && box.height.isFinite() && box.width > 0f && box.height > 0f) { "Invalid page geometry" }
                        return PDRectangle().apply {
                            lowerLeftX = box.lowerLeftX; lowerLeftY = box.lowerLeftY
                            upperRightX = box.upperRightX; upperRightY = box.upperRightY
                        }
                    }
                    val pdPage = PDPage(copyBox(media)).apply {
                        cropBox = copyBox(crop)
                        rotation = originalPage.rotation
                        if (originalPage.cosObject.containsKey(COSName.USER_UNIT)) {
                            val unit = originalPage.cosObject.getFloat(COSName.USER_UNIT, 1f)
                            require(unit.isFinite() && unit > 0f) { "Invalid page unit" }
                            cosObject.setFloat(COSName.USER_UNIT, unit)
                        }
                    }
                    val rotation = ((originalPage.rotation % 360) + 360) % 360
                    val pageWidth = if (rotation == 90 || rotation == 270) crop.height else crop.width
                    val pageHeight = if (rotation == 90 || rotation == 270) crop.width else crop.height

                    val maxDim = maxOf(pageWidth, pageHeight)
                    val renderScale = if (maxDim > 0f) minOf(2f, 2048f / maxDim) else 1f
                    val bmpWidth = (pageWidth * renderScale).toInt().coerceIn(1, 2048)
                    val bmpHeight = (pageHeight * renderScale).toInt().coerceIn(1, 2048)

                    bitmap = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(bmpWidth, bmpHeight); Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888) }
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    val rawResult = ocrBackend.recognize(bitmap)
                    val ocrResult = OcrResultBounds.enforce(rawResult, bmpWidth.toFloat(), bmpHeight.toFloat())

                    outputDoc.addPage(pdPage)

                    val pdImage = JPEGFactory.createFromImage(outputDoc, bitmap, 0.85f)
                    contentStream = PDPageContentStream(outputDoc, pdPage)
                    // Both the raster and OCR boxes use the displayed crop plane.
                    // Map that plane back to PDF user coordinates, retaining /Rotate.
                    val x = crop.lowerLeftX; val y = crop.lowerLeftY
                    val w = crop.width; val h = crop.height
                    contentStream.transform(when (rotation) {
                        90 -> Matrix(0f, 1f, -1f, 0f, x + w, y)
                        180 -> Matrix(-1f, 0f, 0f, -1f, x + w, y + h)
                        270 -> Matrix(0f, -1f, 1f, 0f, x, y + h)
                        else -> Matrix(1f, 0f, 0f, 1f, x, y)
                    })
                    contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)

                    if (ocrResult.blocks.isNotEmpty()) {
                        val extGState = PDExtendedGraphicsState().apply {
                            nonStrokingAlphaConstant = 0.0f
                        }
                        contentStream.setGraphicsStateParameters(extGState)

                        for (block in ocrResult.blocks) {
                            for (line in block.lines) {
                                for (element in line.elements) {
                                    val box = element.boundingBox ?: continue
                                    val text = element.text.trim()
                                    if (text.isEmpty()) continue

                                    val scaleX = pageWidth / bmpWidth.toFloat()
                                    val scaleY = pageHeight / bmpHeight.toFloat()

                                    val x = (box.left * scaleX).coerceIn(0f, pageWidth)
                                    val y = (pageHeight - (box.bottom * scaleY)).coerceIn(0f, pageHeight)
                                    val elementHeight = (box.height() * scaleY).coerceIn(0f, pageHeight)

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
                onProgress(i + 1, pageCount, false)
            }
            onProgress(pageCount, pageCount, true)

            FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                outputDoc.save(outStream)
            }
            return JSONObject().put("success", true).toString()
        } catch (e: Exception) {
            return JSONObject().put("success", false).put("error", e.message).toString()
        } finally {
            try { outputDoc?.close() } catch (e: Exception) {}
            try { originalDoc?.close() } catch (e: Exception) {}
            try { renderer?.close() } catch (e: Exception) {}
            ocrBackend.close()
        }
    }
}
