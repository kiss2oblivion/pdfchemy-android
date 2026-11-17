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
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.harmony.awt.geom.AffineTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfLayoutEngine {

    private data class PageForm(val form: PDFormXObject, val width: Float, val height: Float)

    // A page form contains page content, not annotation appearances or interactive widgets.
    // Reject these documents before creating output rather than silently discarding visible data.
    private fun requireStaticPages(document: PDDocument) {
        require(document.documentCatalog.acroForm == null && document.pages.all { it.annotations.isEmpty() }) {
            "Flatten forms and annotations before changing page layout"
        }
    }

    private fun importVisiblePage(layers: LayerUtility, document: PDDocument, index: Int): PageForm {
        val page = document.getPage(index)
        val box = page.cropBox
        val x = box.lowerLeftX.toDouble()
        val y = box.lowerLeftY.toDouble()
        val right = box.upperRightX.toDouble()
        val top = box.upperRightY.toDouble()
        require(listOf(x,y,right,top).all { it.isFinite() } && right > x && top > y) { "Invalid crop box" }
        val rotation = ((page.rotation % 360) + 360) % 360
        val transform = when (rotation) {
            0 -> AffineTransform(1.0,0.0,0.0,1.0,-x,-y)
            90 -> AffineTransform(0.0,-1.0,1.0,0.0,-y,right)
            180 -> AffineTransform(-1.0,0.0,0.0,-1.0,right,top)
            270 -> AffineTransform(0.0,1.0,-1.0,0.0,top,-x)
            else -> throw IllegalArgumentException("Invalid page rotation")
        }
        val form = layers.importPageAsForm(document,index)
        form.bBox = box
        // Replace LayerUtility's page-as-form matrix: fit the visible, rotated crop exactly once.
        form.setMatrix(transform)
        val sideways = rotation == 90 || rotation == 270
        return PageForm(form, if (sideways) box.height else box.width, if (sideways) box.width else box.height)
    }


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
                require(totalPages in 1..com.pdfchemy.app.security.SecurityLimits.MAX_PAGE_COUNT) { "Invalid page count" }

                requireStaticPages(srcDoc!!)
                outDoc = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())
                val layerUtil = LayerUtility(outDoc)

                val targetBaseW = targetSize.widthPts
                val targetBaseH = targetSize.heightPts

                for (i in 0 until totalPages) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val imported = importVisiblePage(layerUtil,srcDoc!!,i)
                    val origW = imported.width
                    val origH = imported.height

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

                    val form = imported.form
                    PDPageContentStream(outDoc, newPage).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(Matrix(scale, 0f, 0f, scale, tx, ty))
                        cs.drawForm(form)
                        cs.restoreGraphicsState()
                    }
                }

                com.pdfchemy.app.jail.CapabilityIo.output(context, destUri)?.let { com.pdfchemy.app.security.BoundedOutputStream(it) }?.use { outStream ->
                    outDoc!!.save(outStream)
                } ?: return@withContext false
                true
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            srcDoc = com.pdfchemy.app.jail.CapabilityIo.input(context, sourceUri)?.use {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            } ?: return@withContext false

            val totalSrcPages = srcDoc.numberOfPages
            require(totalSrcPages in 1..com.pdfchemy.app.security.SecurityLimits.MAX_PAGE_COUNT) { "Invalid page count" }

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
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
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

                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val imported = importVisiblePage(layerUtil,srcDoc,currentSrcPage)
                        val form = imported.form
                        val origW = imported.width
                        val origH = imported.height

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
            } ?: return@withContext false
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.e("Failed to generate N-Up layout: ${e.message}", e)
            false
        } finally {
            try { srcDoc?.close() } catch (_: Exception) {}
            try { outDoc?.close() } catch (_: Exception) {}
        }
    }
}
