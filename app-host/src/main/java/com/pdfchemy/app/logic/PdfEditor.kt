// =================================================================================================
// [FEATURE: Visual PDF Editor & Freehand Annotation Engine] (FEATURES_REGISTRY Android  4)
// Highlighting, freehand pen/pencil drawing, shapes, watermarking, and text annotations.
// =================================================================================================

package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class DualPageBitmaps(val leftPage: Bitmap?, val rightPage: Bitmap?)

object PdfEditor {
    suspend fun getPageCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        org.json.JSONObject(PdfGateway.executeEngine(context, "GET_PAGE_COUNT", uri, null, "{}")).getInt("pageCount")
    }
    suspend fun renderPageBitmap(context: Context, uri: Uri, pageIndex: Int, targetWidth: Int = 1080): Bitmap? =
        com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderUriToBitmap(context, uri, pageIndex, targetWidth)

    suspend fun renderDualPageBitmaps(
        context: Context,
        uri: Uri,
        leftIndex: Int,
        rightIndex: Int,
        targetWidth: Int = 720
    ): DualPageBitmaps = withContext(Dispatchers.IO) {
        val leftBmp = renderPageBitmap(context, uri, leftIndex, targetWidth)
        val rightBmp = if (rightIndex >= 0) renderPageBitmap(context, uri, rightIndex, targetWidth) else null
        DualPageBitmaps(leftPage = leftBmp, rightPage = rightBmp)
    }

    suspend fun exportModifiedPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        modifications: Map<Int, PageModification>
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = com.google.gson.Gson().toJson(modifications)
            PdfGateway.executeEngine(context, "EDITOR_EXPORT", sourceUri, destUri, json)
            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfEditor: failed to export modified PDF", e)
            Result.failure(e)
        }
    }

    fun renderAnnotationOverlayBitmap(
        mod: PageModification,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Render Freehand Drawings & Highlighters
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
                pathPaint.alpha = 110 // Translucent highlighter
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

        // 2. Render Text Box Annotations
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
                val padding = 8f * scaleFactor
                val rect = RectF(
                    posX - padding,
                    posY - textHeight - padding,
                    posX + textWidth + padding,
                    posY + padding
                )
                canvas.drawRoundRect(rect, 8f, 8f, bgPaint)
            }

            canvas.drawText(textAnn.text, posX, posY, textPaint)
        }

        // 3. Render Stamps
        val stampBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * (targetWidth / 1000f).coerceAtLeast(1f)
        }
        val stampTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        for (stamp in mod.stamps) {
            val posX = stamp.xRatio * targetWidth
            val posY = stamp.yRatio * targetHeight
            val scaleFactor = (targetWidth / 1000f).coerceAtLeast(1f) * stamp.scale

            val color = stamp.type.colorHex.toInt()
            stampBorderPaint.color = color
            stampTextPaint.color = color
            stampTextPaint.textSize = 28f * scaleFactor

            val text = stamp.type.text
            val textWidth = stampTextPaint.measureText(text)
            val boxWidth = textWidth + (32f * scaleFactor)
            val boxHeight = 54f * scaleFactor

            canvas.save()
            canvas.translate(posX, posY)
            canvas.rotate(stamp.rotation)

            val stampRect = RectF(-boxWidth / 2f, -boxHeight / 2f, boxWidth / 2f, boxHeight / 2f)
            canvas.drawRoundRect(stampRect, 10f * scaleFactor, 10f * scaleFactor, stampBorderPaint)
            canvas.drawText(text, 0f, (boxHeight / 4f), stampTextPaint)

            canvas.restore()
        }

        // 4. Render Redactions (Solid Black Opaque Blocks)
        val redactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
        }
        val redactTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        for (redaction in mod.redactions) {
            val norm = redaction.normalizedRect
            val left = norm.left * targetWidth
            val top = norm.top * targetHeight
            val right = norm.right * targetWidth
            val bottom = norm.bottom * targetHeight
            val rect = RectF(left, top, right, bottom)
            canvas.drawRect(rect, redactPaint)

            if (!redaction.overlayLabel.isNullOrBlank() && rect.width() > 50 && rect.height() > 14) {
                val fontSize = (rect.height() * 0.45f).coerceIn(8f, 20f)
                redactTextPaint.textSize = fontSize
                canvas.drawText(redaction.overlayLabel, rect.centerX(), rect.centerY() + (fontSize / 3f), redactTextPaint)
            }
        }

        return bitmap
    }
}
