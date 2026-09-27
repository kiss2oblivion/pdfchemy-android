package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.utils.AppLogger
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class TargetPaperSize(val displayName: String, val widthPts: Float, val heightPts: Float) {
    A4("A4 (210 x 297 mm)", 595.28f, 841.89f),
    LETTER("US Letter (8.5 x 11 in)", 612.0f, 792.0f),
    LEGAL("US Legal (8.5 x 14 in)", 612.0f, 1008.0f),
    A3("A3 (297 x 420 mm)", 841.89f, 1190.55f),
    A5("A5 (148 x 210 mm)", 419.53f, 595.28f)
}

enum class NUpMode(val pagesPerSheet: Int, val cols: Int, val rows: Int) {
    TWO_UP(2, 1, 2),
    FOUR_UP(4, 2, 2),
    SIX_UP(6, 2, 3)
}

object PdfLayoutEngine {

    suspend fun resizePages(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        targetSize: TargetPaperSize
    ): Boolean = withContext(Dispatchers.IO) {
        var doc: PDDocument? = null
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inStream ->
                doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                if (doc == null) return@withContext false

                val totalPages = doc!!.numberOfPages
                val targetRect = PDRectangle(targetSize.widthPts, targetSize.heightPts)

                for (i in 0 until totalPages) {
                    val page = doc!!.getPage(i)
                    page.mediaBox = targetRect
                    page.cropBox = targetRect
                }

                context.contentResolver.openOutputStream(destUri)?.use { outStream ->
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
            pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
            if (pfd == null) return@withContext false

            renderer = PdfRenderer(pfd)
            val totalSrcPages = renderer.pageCount
            if (totalSrcPages == 0) return@withContext false

            outDoc = PDDocument()
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
                            bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
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

            context.contentResolver.openOutputStream(destUri)?.use { outStream ->
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
