package com.pdfchemy.app.jail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.utils.AppLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream

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
                    renderer = PdfRenderer(sourceFd)
                } catch (e: Exception) {
                    AppLogger.w("PdfEditorWorker: Could not initialize PdfRenderer for redactions", e)
                }
            }

            FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
                document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
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
                            val scale = (maxDim / maxOf(origW, origH)).coerceIn(1.5f, 2.5f)
                            val finalW = (origW * scale).toInt()
                            val finalH = (origH * scale).toInt()

                            baseBmp = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888)
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
                            page.rotation = 0
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
                    continue
                }

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

    private fun renderAnnotationOverlayBitmap(
        mod: PageModification,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (drawing in mod.drawings) {
            if (drawing.points.size < 2) continue
            pathPaint.color = drawing.color
            pathPaint.strokeWidth = drawing.strokeWidth * (targetWidth / 1000f).coerceAtLeast(1f)
            if (drawing.isHighlighter) {
                pathPaint.alpha = 110
            }

            val path = Path()
            val p0 = drawing.points[0]
            path.moveTo(p0.x * targetWidth, p0.y * targetHeight)
            for (i in 1 until drawing.points.size) {
                val p = drawing.points[i]
                path.lineTo(p.x * targetWidth, p.y * targetHeight)
            }
            canvas.drawPath(path, pathPaint)
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (textAnn in mod.textAnnotations) {
            if (textAnn.text.isBlank()) continue
            val posX = textAnn.xRatio * targetWidth
            val posY = textAnn.yRatio * targetHeight
            val scaleFactor = (targetWidth / 1000f).coerceAtLeast(1f)
            val textSize = textAnn.fontSize * scaleFactor * 2.2f
            textPaint.textSize = textSize
            textPaint.color = textAnn.textColor

            val textWidth = textPaint.measureText(textAnn.text)
            val textHeight = textSize

            if (textAnn.backgroundColor != android.graphics.Color.TRANSPARENT) {
                bgPaint.color = textAnn.backgroundColor
                val padding = textHeight * 0.3f
                val rect = RectF(
                    posX - padding,
                    posY - textHeight,
                    posX + textWidth + padding,
                    posY + padding
                )
                canvas.drawRoundRect(rect, padding, padding, bgPaint)
            }

            canvas.drawText(textAnn.text, posX, posY, textPaint)
        }

        val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f * (targetWidth / 1000f).coerceAtLeast(1f)
            isFakeBoldText = true
        }
        val stampTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            isFakeBoldText = true
        }

        for (stamp in mod.stamps) {
            val posX = stamp.xRatio * targetWidth
            val posY = stamp.yRatio * targetHeight
            val scaleFactor = (targetWidth / 1000f).coerceAtLeast(1f) * stamp.scale
            
            val stampColor = stamp.type.colorHex.toInt()
            stampPaint.color = stampColor
            stampTextPaint.color = stampColor

            val textSize = 60f * scaleFactor
            stampTextPaint.textSize = textSize
            
            val textWidth = stampTextPaint.measureText(stamp.type.text)
            val textHeight = textSize
            val paddingX = textWidth * 0.2f
            val paddingY = textHeight * 0.5f

            val rectLeft = posX - (textWidth / 2f) - paddingX
            val rectTop = posY - (textHeight / 2f) - paddingY
            val rectRight = posX + (textWidth / 2f) + paddingX
            val rectBottom = posY + (textHeight / 2f) + paddingY

            canvas.save()
            canvas.translate(posX, posY)
            canvas.rotate(stamp.rotation)
            canvas.translate(-posX, -posY)

            val cornerRadius = 15f * scaleFactor
            canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, cornerRadius, cornerRadius, stampPaint)
            canvas.drawText(stamp.type.text, rectLeft + paddingX, posY + textHeight * 0.35f, stampTextPaint)

            canvas.restore()
        }

        return bitmap
    }
}
