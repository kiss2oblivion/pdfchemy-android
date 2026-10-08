package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.jail.AppLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream

object PdfEditorWorker {
    suspend fun exportModifiedPdf(
        context: Context,
        sourceFd: ParcelFileDescriptor,
        targetFd: ParcelFileDescriptor,
        modifications: Map<Int, PageModification>
    ): Boolean = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        var document: PDDocument? = null
        val hasAnyRedactions = modifications.values.any { it.redactions.isNotEmpty() }
        var renderer: PdfRenderer? = null
        try {
            if (hasAnyRedactions) {
                try {
                    renderer = PdfRenderer(sourceFd.dup())
                } catch (e: Exception) {
                    AppLogger.w("PdfEditorWorker: Could not initialize PdfRenderer for redactions", e)
                }
            }

            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.pdfchemy.app.jail.JailMemory.settings())
            }
            val totalPages = document?.numberOfPages ?: 0

            for (pageIdx in (totalPages - 1) downTo 0) {
                val mod = modifications[pageIdx] ?: continue

                if (mod.isDeleted) {
                    document?.removePage(pageIdx)
                    continue
                }

                val page = document?.getPage(pageIdx) ?: continue

                if (mod.redactions.isNotEmpty()) {
                    var rasterized = false
                    if (renderer != null && pageIdx in 0 until renderer.pageCount) {
                        var renderPage: PdfRenderer.Page? = null
                        var baseBmp: Bitmap? = null
                        try {
                            renderPage = renderer.openPage(pageIdx)
                            val origW = renderPage.width.coerceAtLeast(1)
                            val origH = renderPage.height.coerceAtLeast(1)
                            val maxDim = 2400f
                            val scale = minOf(2.5f, maxDim / maxOf(origW, origH), com.pdfchemy.app.security.SecurityLimits.MAX_RENDER_DIMENSION.toFloat() / maxOf(origW, origH))
                            val finalW = (origW * scale).toInt()
                            val finalH = (origH * scale).toInt()

                            baseBmp = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(finalW, finalH); Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888) }
                            val canvas = Canvas(baseBmp)
                            canvas.drawColor(android.graphics.Color.WHITE)

                            renderPage.render(
                                baseBmp,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                            )

                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.BLACK
                                style = Paint.Style.FILL
                            }
                            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = android.graphics.Color.WHITE
                                isFakeBoldText = true
                            }

                            // If the page also has drawings, text annotations, or stamps, draw them onto the base raster
                            if (mod.drawings.isNotEmpty() || mod.textAnnotations.isNotEmpty() || mod.stamps.isNotEmpty()) {
                                val overlayBmp = renderAnnotationOverlayBitmap(mod, finalW, finalH)
                                if (overlayBmp != null) {
                                    canvas.drawBitmap(overlayBmp, 0f, 0f, null)
                                    try { overlayBmp.recycle() } catch (ignored: Exception) {}
                                }
                            }

                            for (rBox in mod.redactions) {
                                val rectF = rBox.normalizedRect
                                val left = rectF.left * finalW
                                val top = rectF.top * finalH
                                val right = rectF.right * finalW
                                val bottom = rectF.bottom * finalH

                                canvas.drawRect(left, top, right, bottom, paint)

                                if (rBox.overlayLabel?.isNotEmpty() == true) {
                                    val bw = right - left
                                    val bh = bottom - top
                                    val ts = (bh * 0.4f).coerceIn(12f, 60f)
                                    textPaint.textSize = ts
                                    val tw = textPaint.measureText(rBox.overlayLabel!!)
                                    val tx = left + (bw - tw) / 2f
                                    val ty = top + (bh + ts * 0.7f) / 2f
                                    canvas.drawText(rBox.overlayLabel!!, tx, ty, textPaint)
                                }
                            }

                            val pdImage = LosslessFactory.createFromImage(document, baseBmp)
                            page.rotation = if (mod.rotationDegrees != 0) (mod.rotationDegrees % 360 + 360) % 360 else 0
                            page.cropBox = null
                            page.mediaBox = PDRectangle(finalW.toFloat(), finalH.toFloat())
                            page.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.ANNOTS)

                            PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false).use { cs ->
                                cs.drawImage(pdImage, 0f, 0f, finalW.toFloat(), finalH.toFloat())
                            }
                            rasterized = true
                        } catch (e: Exception) {
                            AppLogger.w("PdfEditorWorker: renderer-based redaction flattening failed for page ${pageIdx}: ${e.message}")
                        } finally {
                            try { renderPage?.close() } catch (e: Exception) {}
                        }
                    }

                    if (!rasterized) {
                        throw SecurityException("Cannot forensically rasterize the PDF page because PdfRenderer is unavailable. Aborting redaction to ensure maximum security without data loss.")
                    }
                } else {
                    if (mod.rotationDegrees != 0) {
                    val currentRotation = page.rotation
                    page.rotation = (currentRotation + mod.rotationDegrees) % 360
                }

                if (mod.drawings.isNotEmpty() || mod.textAnnotations.isNotEmpty() || mod.stamps.isNotEmpty()) {
                    val cropBox = page.cropBox ?: page.mediaBox
                    val pageWidthPts = cropBox.width
                    val pageHeightPts = cropBox.height
                    val lowerLeftX = cropBox.lowerLeftX
                    val lowerLeftY = cropBox.lowerLeftY
                    val rot = page.rotation

                    val dispW = if (rot == 90 || rot == 270) pageHeightPts else pageWidthPts
                    val dispH = if (rot == 90 || rot == 270) pageWidthPts else pageHeightPts

                    val overlayBmp = renderAnnotationOverlayBitmap(
                        mod = mod,
                        targetWidth = (dispW * 2).toInt(),
                        targetHeight = (dispH * 2).toInt()
                    )

                    if (overlayBmp != null) {
                        try {
                            val pdImage = LosslessFactory.createFromImage(document, overlayBmp)
                            val contentStream = PDPageContentStream(
                                document,
                                page,
                                PDPageContentStream.AppendMode.APPEND,
                                true,
                                true
                            )
                            contentStream.use { cs ->
                                cs.saveGraphicsState()
                                when (rot) {
                                    90 -> {
                                        cs.transform(Matrix(0f, 1f, -1f, 0f, lowerLeftX + pageWidthPts, lowerLeftY))
                                        cs.drawImage(pdImage, 0f, 0f, pageHeightPts, pageWidthPts)
                                    }
                                    180 -> {
                                        cs.transform(Matrix(-1f, 0f, 0f, -1f, lowerLeftX + pageWidthPts, lowerLeftY + pageHeightPts))
                                        cs.drawImage(pdImage, 0f, 0f, pageWidthPts, pageHeightPts)
                                    }
                                    270 -> {
                                        cs.transform(Matrix(0f, -1f, 1f, 0f, lowerLeftX, lowerLeftY + pageHeightPts))
                                        cs.drawImage(pdImage, 0f, 0f, pageHeightPts, pageWidthPts)
                                    }
                                    else -> {
                                        cs.drawImage(pdImage, lowerLeftX, lowerLeftY, pageWidthPts, pageHeightPts)
                                    }
                                }
                                cs.restoreGraphicsState()
                            }
                        } finally {
                            overlayBmp.recycle()
                        }
                    }
                }
                }
            }

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                document?.save(out)
                out.flush()
            }

            true
        } catch (e: Exception) {
            AppLogger.e("PdfEditorWorker: failed to export modified PDF", e)
            false
        } finally {
            try { renderer?.close() } catch (e: Exception) {}
            try { document?.close() } catch (e: Exception) {}
        }
    }

    private fun renderAnnotationOverlayBitmap(mod: PageModification, targetWidth: Int, targetHeight: Int): Bitmap? =
        com.pdfchemy.app.logic.AnnotationRenderer.render(mod.copy(redactions = emptyList()), targetWidth, targetHeight)
}
