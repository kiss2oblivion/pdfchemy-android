package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.AppLogger
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
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
        var doc: PDDocument? = null
        try {
            com.pdfchemy.app.jail.CapabilityIo.input(context, sourceUri)?.use { inStream ->
                doc = PDDocument.load(inStream, com.pdfchemy.app.jail.JailMemory.settings())
                if (doc == null) return@withContext false

                val totalPages = doc!!.numberOfPages
                val targetRect = PDRectangle(targetSize.widthPts, targetSize.heightPts)

                for (i in 0 until totalPages) {
                    val page = doc!!.getPage(i)
                    page.mediaBox = targetRect
                    page.cropBox = targetRect
                }

                com.pdfchemy.app.jail.CapabilityIo.output(context, destUri)?.let { com.pdfchemy.app.security.BoundedOutputStream(it) }?.use { outStream ->
                    doc!!.save(outStream)
                }
                true
            } ?: false
        } catch (e: Exception) {
            AppLogger.e("Failed to resize PDF pages: ${e.message}", e)
            false
        } finally {
            doc?.close()
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
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var outDoc: PDDocument? = null
        try {
            pfd = com.pdfchemy.app.jail.CapabilityIo.descriptor(context, sourceUri, "r")
            if (pfd == null) return@withContext false

            renderer = PdfRenderer(pfd)
            val totalSrcPages = renderer.pageCount
            if (totalSrcPages == 0) return@withContext false

            outDoc = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())
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

                        // Render source page into bitmap
                        val srcPage = renderer.openPage(currentSrcPage)
                        var bitmap: Bitmap? = null
                        try {
                            val renderScale = 2f
                            val bmpW = (srcPage.width * renderScale).toInt().coerceIn(100, 2048)
                            val bmpH = (srcPage.height * renderScale).toInt().coerceIn(100, 2048)
                            bitmap = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(bmpW, bmpH); Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888) }
                            val canvas = android.graphics.Canvas(bitmap)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            srcPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                            // Calculate fit scaling within cell
                            val pad = 6f
                            val availW = cellW - (pad * 2f)
                            val availH = cellH - (pad * 2f)
                            val scale = minOf(availW / bmpW, availH / bmpH)
                            val drawW = bmpW * scale
                            val drawH = bmpH * scale
                            val drawX = x + pad + ((availW - drawW) / 2f)
                            val drawY = y + pad + ((availH - drawH) / 2f)

                            val pdImage = JPEGFactory.createFromImage(outDoc, bitmap, 0.85f)
                            cs.drawImage(pdImage, drawX, drawY, drawW, drawH)

                            if (drawBorders) {
                                cs.setStrokingColor(200, 200, 200)
                                cs.setLineWidth(0.5f)
                                cs.addRect(drawX, drawY, drawW, drawH)
                                cs.stroke()
                            }
                        } finally {
                            bitmap?.recycle()
                            srcPage.close()
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
            outDoc?.close()
            renderer?.close()
            pfd?.close()
        }
    }
}
