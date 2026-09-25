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

enum class EditorTool { VIEW, PEN, HIGHLIGHTER, TEXT, STAMP, REDACT }
enum class StampType(val text: String, val colorHex: Long) {
    APPROVED("APPROVED", 0xFF2E7D32),
    CONFIDENTIAL("CONFIDENTIAL", 0xFFC62828),
    DRAFT("DRAFT", 0xFFEF6C00),
    PAID("PAID", 0xFF1565C0),
    REJECTED("REJECTED", 0xFFB71C1C),
    FINAL("FINAL", 0xFF4527A0),
    URGENT("URGENT", 0xFFD84315)
}
data class DrawingPoint(val x: Float, val y: Float)
data class DrawingPath(val id: String = UUID.randomUUID().toString(), val points: List<DrawingPoint>, val color: Int, val strokeWidth: Float, val isHighlighter: Boolean = false)
data class TextAnnotation(val id: String = UUID.randomUUID().toString(), val text: String, val xRatio: Float, val yRatio: Float, val fontSize: Float = 16f, val textColor: Int = android.graphics.Color.BLACK, val backgroundColor: Int = android.graphics.Color.TRANSPARENT)
data class StampAnnotation(val id: String = UUID.randomUUID().toString(), val type: StampType, val xRatio: Float, val yRatio: Float, val scale: Float = 1.0f, val rotation: Float = -15f)
data class PageModification(val pageIndex: Int, val rotationDegrees: Int = 0, val isDeleted: Boolean = false, val drawings: List<DrawingPath> = emptyList(), val textAnnotations: List<TextAnnotation> = emptyList(), val stamps: List<StampAnnotation> = emptyList(), val redactions: List<com.pdfchemy.app.logic.RedactionBox> = emptyList()) {
    val hasChanges: Boolean get() = rotationDegrees != 0 || isDeleted || drawings.isNotEmpty() || textAnnotations.isNotEmpty() || stamps.isNotEmpty() || redactions.isNotEmpty()
}
data class DualPageBitmaps(val leftPage: Bitmap?, val rightPage: Bitmap?)

object PdfEditor {
    fun getPageCount(context: Context, uri: Uri): Int {
        return kotlinx.coroutines.runBlocking {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runBlocking 0
                com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
            } catch (e: Exception) {
                AppLogger.e("PdfEditor: failed to get page count", e)
                0
            } finally {
                try { pfd?.close() } catch (e: Exception) {}
            }
        }
    }

    suspend fun renderPageBitmap(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        targetWidth: Int = 1080
    ): Bitmap? = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var jpegPfd: ParcelFileDescriptor? = null
        var tempJpeg: File? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) return@withContext null

            tempJpeg = File.createTempFile("page_${pageIndex}_", ".jpg", context.cacheDir)
            jpegPfd = ParcelFileDescriptor.open(tempJpeg, ParcelFileDescriptor.MODE_READ_WRITE)

            val success = com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderPageToJpeg(
                context, pfd, pageIndex, jpegPfd
            )

            if (success == true) {
                android.graphics.BitmapFactory.decodeFile(tempJpeg.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e("PdfEditor: renderPageBitmap failed for page $pageIndex", e)
            null
        } finally {
            try { pfd?.close() } catch (e: Exception) {}
            try { jpegPfd?.close() } catch (e: Exception) {}
            tempJpeg?.delete()
        }
    }

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
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val json = mapper.writeValueAsString(modifications)
            val success = com.pdfchemy.app.jail.PdfJailClient.exportModifiedPdf(context, sourceUri, destUri, json)
            if (success) {
                Result.success(true)
            } else {
                Result.failure(Exception("Jail Client returned false"))
            }
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
