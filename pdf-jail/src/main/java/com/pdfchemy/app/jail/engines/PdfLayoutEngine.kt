package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.AppLogger
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

                val targetBaseW = targetSize.widthPts
                val targetBaseH = targetSize.heightPts

                for (i in 0 until totalPages) {
                    val srcPage = srcDoc!!.getPage(i)
                    val srcBox = srcPage.cropBox ?: srcPage.mediaBox ?: PDRectangle.A4
                    val origW = srcBox.width
                    val origH = srcBox.height

                    // Preserve page orientation (portrait vs landscape)
                    val isLandscape = origW > origH
                    val targetW = if (isLandscape) maxOf(targetBaseW, targetBaseH) else minOf(targetBaseW, targetBaseH)
                    val targetH = if (isLandscape) minOf(targetBaseW, targetBaseH) else maxOf(targetBaseW, targetBaseH)

                    val targetRect = PDRectangle(targetW, targetH)
                    val newPage = PDPage(targetRect)
                    outDoc!!.addPage(newPage)

                    // Proportional scale factor
                    val scale = minOf(targetW / origW, targetH / origH)
                    val scaledW = origW * scale
                    val scaledH = origH * scale
                    val tx = (targetW - scaledW) / 2f
                    val ty = (targetH - scaledH) / 2f

                    val form = layerUtil.importPageAsForm(srcDoc, i)
                    PDPageContentStream(outDoc, newPage).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(Matrix(scale, 0f, 0f, scale, tx, ty))
                        cs.drawForm(form)
                        cs.restoreGraphicsState()
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
            try { srcDoc?.close() } catch (_: Exception) {}
            try { outDoc?.close() } catch (_: Exception) {}
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
            srcDoc = com.pdfchemy.app.jail.CapabilityIo.input(context, sourceUri)?.let {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            } ?: return@withContext false

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

                PDPageContentStream(outDoc, sheetPage).use { cs ->
                    for (slot in 0 until pagesPerSheet) {
                        if (currentSrcPage >= totalSrcPages) break

                        val col = slot % cols
                        val row = slot / cols

                        // PDF Y origin is bottom-left
                        val x = marginX + (col * cellW)
                        val y = targetSize.heightPts - marginY - ((row + 1) * cellH)

                        val form = layerUtil.importPageAsForm(srcDoc, currentSrcPage)
                        val origBBox = form.bBox
                        val origW = if (origBBox.width > 0) origBBox.width else 595.28f
                        val origH = if (origBBox.height > 0) origBBox.height else 841.89f

                        // Calculate fit scaling within cell
                        val pad = 6f
                        val availW = cellW - (pad * 2f)
                        val availH = cellH - (pad * 2f)
                        val scale = minOf(availW / origW, availH / origH)
                        val drawW = origW * scale
                        val drawH = origH * scale
                        val drawX = x + pad + ((availW - drawW) / 2f)
                        val drawY = y + pad + ((availH - drawH) / 2f)

                        cs.saveGraphicsState()
                        cs.transform(Matrix(scale, 0f, 0f, scale, drawX, drawY))
                        cs.drawForm(form)
                        cs.restoreGraphicsState()

                        if (drawBorders) {
                            cs.setStrokingColor(0.784f, 0.784f, 0.784f)
                            cs.setLineWidth(0.5f)
                            cs.addRect(drawX, drawY, drawW, drawH)
                            cs.stroke()
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
            try { srcDoc?.close() } catch (_: Exception) {}
            try { outDoc?.close() } catch (_: Exception) {}
        }
    }
}
