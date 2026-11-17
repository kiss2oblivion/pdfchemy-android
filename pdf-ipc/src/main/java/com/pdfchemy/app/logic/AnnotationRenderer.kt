package com.pdfchemy.app.logic
import android.graphics.*
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
object AnnotationRenderer {
    fun render(
        mod: PageModification,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        com.pdfchemy.app.security.SecurityLimits.requirePixels(targetWidth, targetHeight)
        val bitmap = createBitmap(targetWidth, targetHeight)
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
            pathPaint.strokeWidth = drawing.strokeWidth * (targetWidth / 1000f)
            pathPaint.alpha = if (drawing.isHighlighter) 110 else android.graphics.Color.alpha(drawing.color)

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
            val scaleFactor = (targetWidth / 1000f)
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
                canvas.drawRoundRect(rect, 8f * scaleFactor, 8f * scaleFactor, bgPaint)
            }

            canvas.drawText(textAnn.text, posX, posY, textPaint)
        }

        // 3. Render Stamps
        val stampBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * (targetWidth / 1000f)
        }
        val stampTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        for (stamp in mod.stamps) {
            val posX = stamp.xRatio * targetWidth
            val posY = stamp.yRatio * targetHeight
            val scaleFactor = (targetWidth / 1000f) * stamp.scale

            val color = stamp.type.colorHex.toInt()
            stampBorderPaint.color = color
            stampTextPaint.color = color
            stampTextPaint.textSize = 28f * scaleFactor

            val text = stamp.customText?.take(80)?.takeIf { it.isNotBlank() } ?: stamp.type.text
            val textWidth = stampTextPaint.measureText(text)
            val boxWidth = textWidth + (32f * scaleFactor)
            val boxHeight = 54f * scaleFactor

            canvas.withTranslation(posX, posY) {
                rotate(stamp.rotation)

                val stampRect = RectF(-boxWidth / 2f, -boxHeight / 2f, boxWidth / 2f, boxHeight / 2f)
                drawRoundRect(stampRect, 10f * scaleFactor, 10f * scaleFactor, stampBorderPaint)
                drawText(text, 0f, (boxHeight / 4f), stampTextPaint)
            }
        }

        // 3b. Render Vector Shapes
        if (mod.shapes.isNotEmpty()) {
            val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val shapeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }
            val scaleFactor = targetWidth / 1000f
            for (shape in mod.shapes) {
                val startX = shape.startXRatio * targetWidth
                val startY = shape.startYRatio * targetHeight
                val endX = shape.endXRatio * targetWidth
                val endY = shape.endYRatio * targetHeight
                shapePaint.color = shape.strokeColor
                shapePaint.strokeWidth = shape.strokeWidth * scaleFactor

                val left = minOf(startX, endX)
                val top = minOf(startY, endY)
                val right = maxOf(startX, endX)
                val bottom = maxOf(startY, endY)

                when (shape.type) {
                    ShapeType.RECTANGLE -> {
                        if (shape.fillColor != Color.TRANSPARENT) {
                            shapeFillPaint.color = shape.fillColor
                            canvas.drawRect(left, top, right, bottom, shapeFillPaint)
                        }
                        canvas.drawRect(left, top, right, bottom, shapePaint)
                    }
                    ShapeType.CIRCLE -> {
                        val oval = RectF(left, top, right, bottom)
                        if (shape.fillColor != Color.TRANSPARENT) {
                            shapeFillPaint.color = shape.fillColor
                            canvas.drawOval(oval, shapeFillPaint)
                        }
                        canvas.drawOval(oval, shapePaint)
                    }
                    ShapeType.LINE -> {
                        canvas.drawLine(startX, startY, endX, endY, shapePaint)
                    }
                    ShapeType.ARROW -> {
                        canvas.drawLine(startX, startY, endX, endY, shapePaint)
                        val dx = (endX - startX).toDouble()
                        val dy = (endY - startY).toDouble()
                        val length = kotlin.math.hypot(dx, dy).toFloat()
                        if (length > 5f) {
                            val arrowHeadLength = (20f * scaleFactor).coerceAtMost(length * 0.4f)
                            val arrowHeadAngle = Math.toRadians(25.0)
                            val angle = kotlin.math.atan2(dy, dx)
                            val x1 = (endX - arrowHeadLength * kotlin.math.cos(angle - arrowHeadAngle)).toFloat()
                            val y1 = (endY - arrowHeadLength * kotlin.math.sin(angle - arrowHeadAngle)).toFloat()
                            val x2 = (endX - arrowHeadLength * kotlin.math.cos(angle + arrowHeadAngle)).toFloat()
                            val y2 = (endY - arrowHeadLength * kotlin.math.sin(angle + arrowHeadAngle)).toFloat()
                            val arrowPath = Path().apply {
                                moveTo(endX, endY)
                                lineTo(x1, y1)
                                lineTo(x2, y2)
                                close()
                            }
                            shapeFillPaint.color = shape.strokeColor
                            canvas.drawPath(arrowPath, shapeFillPaint)
                        }
                    }
                }
            }
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

            val labelScale = targetWidth / 1000f
            if (!redaction.overlayLabel.isNullOrBlank() && rect.width() > 50 * labelScale && rect.height() > 14 * labelScale) {
                val fontSize = (rect.height() * 0.45f).coerceIn(8f * labelScale, 20f * labelScale)
                redactTextPaint.textSize = fontSize
                canvas.drawText(redaction.overlayLabel, rect.centerX(), rect.centerY() + (fontSize / 3f), redactTextPaint)
            }
        }

        return bitmap
    }
}
