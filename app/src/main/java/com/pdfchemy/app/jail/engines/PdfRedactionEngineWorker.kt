package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.sandbox.NativeRendererCoordinator
import com.pdfchemy.app.logic.RedactionBox
import com.pdfchemy.app.logic.RedactionConfig
import com.pdfchemy.app.utils.AppLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfRedactionEngineWorker {
    fun execute(context: Context, sourceFd: ParcelFileDescriptor?, destFd: ParcelFileDescriptor?, paramsJson: String): String {
        return try {
            val params = JSONObject(paramsJson)
            val configObj = params.getJSONObject("config")
            val config = RedactionConfig(
                isBlackout = configObj.optBoolean("isBlackout", true),
                defaultOverlayText = configObj.optString("defaultOverlayText", ""),
                forensicSanitize = configObj.optBoolean("forensicSanitize", true)
            )
            
            val boxesArr = params.getJSONArray("boxes")
            val boxes = mutableListOf<RedactionBox>()
            for (i in 0 until boxesArr.length()) {
                val box = boxesArr.getJSONObject(i)
                boxes.add(RedactionBox(
                    pageIndex = box.getInt("pageIndex"),
                    xRatio = box.getDouble("xRatio").toFloat(),
                    yRatio = box.getDouble("yRatio").toFloat(),
                    widthRatio = box.getDouble("widthRatio").toFloat(),
                    heightRatio = box.getDouble("heightRatio").toFloat()
                ))
            }

            val redactedCount = runBlocking {
                applyRedactions(context, sourceFd!!, destFd!!, boxes, config)
            }
            JSONObject().put("success", true).put("count", redactedCount).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    private suspend fun applyRedactions(
        context: Context,
        sourceFd: ParcelFileDescriptor,
        destFd: ParcelFileDescriptor,
        boxes: List<RedactionBox>,
        config: RedactionConfig
    ): Int {
        if (boxes.isEmpty()) {
            FileInputStream(sourceFd.fileDescriptor).use { input ->
                FileOutputStream(destFd.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
            return 0
        }

        PDFBoxResourceLoader.init(context)
        var document: PDDocument? = null

        try {
            FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                document = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            }
            val pageCount = document!!.numberOfPages
            if (pageCount == 0) return 0

            val font = PDType1Font.HELVETICA_BOLD
            val boxesByPage = boxes.groupBy { it.pageIndex }

            for ((pageIdx, pageBoxes) in boxesByPage) {
                if (pageIdx < 0 || pageIdx >= pageCount) continue

                val page = document!!.getPage(pageIdx)
                val cropBox = page.cropBox ?: page.mediaBox
                val pw = cropBox.width
                val ph = cropBox.height
                val rot = ((page.rotation % 360) + 360) % 360

                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (box in pageBoxes) {
                        val drawX = when (rot) {
                            90 -> cropBox.lowerLeftX + pw - (box.yRatio.coerceIn(0f, 1f) + box.heightRatio.coerceIn(0.01f, 1f)) * pw
                            180 -> cropBox.lowerLeftX + pw - (box.xRatio.coerceIn(0f, 1f) + box.widthRatio.coerceIn(0.01f, 1f)) * pw
                            270 -> cropBox.lowerLeftX + box.yRatio.coerceIn(0f, 1f) * pw
                            else -> cropBox.lowerLeftX + (box.xRatio.coerceIn(0f, 1f) * pw)
                        }

                        val drawY = when (rot) {
                            90 -> cropBox.lowerLeftY + box.xRatio.coerceIn(0f, 1f) * ph
                            180 -> cropBox.lowerLeftY + box.yRatio.coerceIn(0f, 1f) * ph
                            270 -> cropBox.lowerLeftY + ph - (box.xRatio.coerceIn(0f, 1f) + box.widthRatio.coerceIn(0.01f, 1f)) * ph
                            else -> cropBox.lowerLeftY + ph - (box.yRatio.coerceIn(0f, 1f) + box.heightRatio.coerceIn(0.01f, 1f)) * ph
                        }

                        val drawW = if (rot == 90 || rot == 270) box.heightRatio.coerceIn(0.01f, 1f) * pw else box.widthRatio.coerceIn(0.01f, 1f) * pw
                        val drawH = if (rot == 90 || rot == 270) box.widthRatio.coerceIn(0.01f, 1f) * ph else box.heightRatio.coerceIn(0.01f, 1f) * ph

                        val wv = box.widthRatio.coerceIn(0.01f, 1f) * pw
                        val hv = box.heightRatio.coerceIn(0.01f, 1f) * ph

                        cs.saveGraphicsState()
                        if (config.isBlackout) {
                            cs.setNonStrokingColor(0, 0, 0)
                        } else {
                            cs.setNonStrokingColor(255, 255, 255)
                        }
                        cs.addRect(drawX, drawY, drawW, drawH)
                        cs.fill()
                        cs.restoreGraphicsState()

                        val overlay = config.defaultOverlayText
                        if (overlay.isNotBlank() && wv > 30f && hv > 10f) {
                            val fontSize = (hv * 0.6f).coerceIn(6f, 10f)
                            val textW = (font.getStringWidth(overlay) / 1000f) * fontSize
                            if (textW < wv) {
                                cs.saveGraphicsState()
                                cs.beginText()
                                cs.setFont(font, fontSize)
                                if (config.isBlackout) {
                                    cs.setNonStrokingColor(255, 255, 255)
                                } else {
                                    cs.setNonStrokingColor(80, 80, 80)
                                }
                                
                                val rad = Math.toRadians((360 - rot).toDouble())
                                val tx = when (rot) {
                                    90 -> drawX + drawW - ((drawW - fontSize) / 2f)
                                    180 -> drawX + drawW - ((drawW - textW) / 2f)
                                    270 -> drawX + ((drawW - fontSize) / 2f)
                                    else -> drawX + ((drawW - textW) / 2f)
                                }
                                val ty = when (rot) {
                                    90 -> drawY + drawH - ((drawH - textW) / 2f)
                                    180 -> drawY + drawH - ((drawH - fontSize) / 2f)
                                    270 -> drawY + ((drawH - textW) / 2f)
                                    else -> drawY + ((drawH - fontSize) / 2f)
                                }
                                
                                val matrix = com.tom_roush.pdfbox.util.Matrix.getRotateInstance(rad, tx, ty)
                                cs.setTextMatrix(matrix)
                                cs.showText(overlay)
                                cs.endText()
                                cs.restoreGraphicsState()
                            }
                        }
                    }
                }
            }

            val tempFile = File(context.cacheDir, "redacted_" + System.currentTimeMillis() + ".pdf")
            var rasterFile: File? = null
            var pfd: ParcelFileDescriptor? = null
            var newDoc: PDDocument? = null

            try {
                document!!.save(tempFile)
                document!!.close()
                document = null

                val finalFile = if (config.forensicSanitize) {
                    rasterFile = File(context.cacheDir, "rasterized_" + System.currentTimeMillis() + ".pdf")
                    pfd = try { ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY) } catch (_: Exception) { null }
                    val renderedPageCount = if (pfd != null) NativeRendererCoordinator.getPageCount(context, pfd) else null
                    val memSettings = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(10 * 1024 * 1024, 250 * 1024 * 1024)
                    val baseDoc = PDDocument.load(tempFile, memSettings)
                    
                    if (renderedPageCount != null) {
                        newDoc = PDDocument()
                        try {
                            for (i in 0 until renderedPageCount) {
                                if (boxesByPage.containsKey(i)) {
                                    val pipe = ParcelFileDescriptor.createPipe()
                                    val readFd = pipe[0]
                                    val writeFd = pipe[1]
                                    var pdImage: com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject? = null
                                    
                                    val renderJob = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) {
                                        NativeRendererCoordinator.renderPageToJpeg(context, pfd!!, i, writeFd)
                                    }
                                    
                                    try {
                                        ParcelFileDescriptor.AutoCloseInputStream(readFd).use { pipeIn ->
                                            pdImage = JPEGFactory.createFromStream(newDoc, pipeIn)
                                        }
                                    } finally {
                                        renderJob.await()
                                    }
                                    
                                    if (pdImage != null) {
                                        val pdfPage = PDPage(PDRectangle(pdImage!!.width.toFloat(), pdImage!!.height.toFloat()))
                                        newDoc!!.addPage(pdfPage)
                                        PDPageContentStream(newDoc, pdfPage).use { cs ->
                                            cs.drawImage(pdImage!!, 0f, 0f, pdImage!!.width.toFloat(), pdImage!!.height.toFloat())
                                        }
                                    } else {
                                        throw SecurityException("Native renderer failed to yield valid image data.")
                                    }
                                } else {
                                    if (i < baseDoc.numberOfPages) {
                                        newDoc!!.importPage(baseDoc.getPage(i))
                                    }
                                }
                            }
                        } finally {
                            try { baseDoc.close() } catch (_: Exception) {}
                        }
                        try { pfd?.close() } catch (_: Exception) {}
                        pfd = null
                        
                        newDoc!!.save(rasterFile)
                        newDoc!!.close()
                        newDoc = null
                        tempFile.delete()
                        rasterFile
                    } else {
                        baseDoc.close()
                        try { pfd?.close() } catch (_: Exception) {}
                        tempFile.delete()
                        throw SecurityException("Cannot forensically rasterize the PDF because NativeRendererCoordinator is unavailable. Aborting redaction to ensure maximum security without data loss.")
                    }
                } else {
                    tempFile
                }

                finalFile.inputStream().use { inp ->
                    FileOutputStream(destFd.fileDescriptor).use { output ->
                        inp.copyTo(output)
                    }
                }

                return boxes.size
            } finally {
                try { pfd?.close() } catch (_: Exception) {}
                try { newDoc?.close() } catch (_: Exception) {}
                if (tempFile.exists()) tempFile.delete()
                if (rasterFile != null && rasterFile.exists()) rasterFile.delete()
            }
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }
}
