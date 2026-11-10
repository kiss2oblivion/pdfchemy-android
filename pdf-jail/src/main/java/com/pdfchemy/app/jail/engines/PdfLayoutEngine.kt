package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.*

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.jail.AppLogger
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfLayoutEngine {

    suspend fun resizePages(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        targetSize: TargetPaperSize
    ): Boolean = withContext(Dispatchers.IO) {
        var srcDoc: PDDocument? = null
        var outDoc: PDDocument? = null
        try {
            com.pdfchemy.app.jail.CapabilityIo.input(context, sourceUri)?.use { inStream ->
                srcDoc = PDDocument.load(inStream, com.pdfchemy.app.jail.JailMemory.settings())
                if (srcDoc == null) return@withContext false

                val totalPages = srcDoc!!.numberOfPages
                if (totalPages == 0) return@withContext false

                outDoc = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())
                val layerUtil = LayerUtility(outDoc)
                val targetRect = PDRectangle(targetSize.widthPts, targetSize.heightPts)

                for (i in 0 until totalPages) {
                    val srcPage = srcDoc!!.getPage(i)
                    val srcBox = srcPage.cropBox ?: srcPage.mediaBox
                    val srcW = srcBox.width
                    val srcH = srcBox.height

                    val newPage = PDPage(targetRect)
                    outDoc!!.addPage(newPage)

                    if (srcW > 0f && srcH > 0f) {
                        val scale = minOf(targetSize.widthPts / srcW, targetSize.heightPts / srcH)
                        val scaledW = srcW * scale
                        val scaledH = srcH * scale
                        val offsetX = (targetSize.widthPts - scaledW) / 2f
                        val offsetY = (targetSize.heightPts - scaledH) / 2f

                        val form = layerUtil.importPageAsForm(srcDoc, i)
                        PDPageContentStream(outDoc, newPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.saveGraphicsState()
                            val matrix = Matrix.getTranslateInstance(offsetX - (srcBox.lowerLeftX * scale), offsetY - (srcBox.lowerLeftY * scale))
                            matrix.concatenate(Matrix.getScaleInstance(scale, scale))
                            cs.transform(matrix)
                            cs.drawForm(form)
                            cs.restoreGraphicsState()
                        }
                    }
                }

                com.pdfchemy.app.jail.CapabilityIo.output(context, destUri)?.let { com.pdfchemy.app.security.BoundedOutputStream(it) }?.use { outStream ->
                    outDoc!!.save(outStream)
                }
                true
            } ?: false
        } catch (e: Exception) {
            AppLogger.e("Failed to resize PDF pages: ${e.message}", e)
            false
        } finally {
            srcDoc?.close()
            outDoc?.close()
        }
    }

    suspend fun createNUpLayout(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        mode: NUpMode,
        targetSize: TargetPaperSize = TargetPaperSize.A4,
        drawBorders: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        var srcDoc: PDDocument? = null
        var outDoc: PDDocument? = null
        try {
            val inStream = com.pdfchemy.app.jail.CapabilityIo.input(context, sourceUri) ?: return@withContext false
            srcDoc = inStream.use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            val totalSrcPages = srcDoc.numberOfPages
            if (totalSrcPages == 0) return@withContext false

            outDoc = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())
            val layerUtil = LayerUtility(outDoc)
            val sheetRect = PDRectangle(targetSize.widthPts, targetSize.heightPts)
            val cols = mode.cols
            val rows = mode.rows
            val pagesPerSheet = mode.pagesPerSheet

            val marginX = 24f
            val marginY = 24f
            val cellW = (targetSize.widthPts - (marginX * 2f)) / cols
            val cellH = (targetSize.heightPts - (marginY * 2f)) / rows

            var currentSrcPage = 0
            while (currentSrcPage < totalSrcPages) {
                val sheetPage = PDPage(sheetRect)
                outDoc.addPage(sheetPage)

                PDPageContentStream(outDoc, sheetPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (slot in 0 until pagesPerSheet) {
                        if (currentSrcPage >= totalSrcPages) break

                        val col = slot % cols
                        val row = slot / cols

                        // PDF Y origin is bottom-left
                        val x = marginX + (col * cellW)
                        val y = targetSize.heightPts - marginY - ((row + 1) * cellH)

                        val srcPage = srcDoc.getPage(currentSrcPage)
                        val srcBox = srcPage.cropBox ?: srcPage.mediaBox
                        val srcW = srcBox.width
                        val srcH = srcBox.height

                        if (srcW > 0f && srcH > 0f) {
                            val pad = 6f
                            val availW = cellW - (pad * 2f)
                            val availH = cellH - (pad * 2f)
                            val scale = minOf(availW / srcW, availH / srcH)
                            val drawW = srcW * scale
                            val drawH = srcH * scale
                            val drawX = x + pad + ((availW - drawW) / 2f)
                            val drawY = y + pad + ((availH - drawH) / 2f)

                            val form = layerUtil.importPageAsForm(srcDoc, currentSrcPage)
                            cs.saveGraphicsState()
                            val matrix = Matrix.getTranslateInstance(drawX - (srcBox.lowerLeftX * scale), drawY - (srcBox.lowerLeftY * scale))
                            matrix.concatenate(Matrix.getScaleInstance(scale, scale))
                            cs.transform(matrix)
                            cs.drawForm(form)
                            cs.restoreGraphicsState()

                            if (drawBorders) {
                                cs.saveGraphicsState()
                                cs.setStrokingColor(200f / 255f, 200f / 255f, 200f / 255f)
                                cs.setLineWidth(0.5f)
                                cs.addRect(drawX, drawY, drawW, drawH)
                                cs.stroke()
                                cs.restoreGraphicsState()
                            }
                        }

                        currentSrcPage++
                    }
                }
            }

            com.pdfchemy.app.jail.CapabilityIo.output(context, destUri)?.let { com.pdfchemy.app.security.BoundedOutputStream(it) }?.use { outStream ->
                outDoc.save(outStream)
            }
            true
        } catch (e: Exception) {
            AppLogger.e("Failed to generate N-Up layout: ${e.message}", e)
            false
        } finally {
            srcDoc?.close()
            outDoc?.close()
        }
    }
}
