package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class NormalizedCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    fun isValid(): Boolean = left < right && top < bottom && left >= 0f && right <= 1f && top >= 0f && bottom <= 1f
}

object PdfCropEngine {
    fun detectContentBounds(bitmap: Bitmap, toleranceThreshold: Int = 245): NormalizedCropRect {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return NormalizedCropRect()

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0

        val stepX = max(1, width / 200)
        val stepY = max(1, height / 200)

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val alpha = Color.alpha(pixel)

                if (alpha > 50 && (r < toleranceThreshold || g < toleranceThreshold || b < toleranceThreshold)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (minX >= maxX || minY >= maxY) {
            return NormalizedCropRect(0.05f, 0.05f, 0.95f, 0.95f)
        }

        val padX = (width * 0.02f).toInt()
        val padY = (height * 0.02f).toInt()

        val cropLeft = max(0, minX - padX).toFloat() / width
        val cropTop = max(0, minY - padY).toFloat() / height
        val cropRight = min(width, maxX + padX).toFloat() / width
        val cropBottom = min(height, maxY + padY).toFloat() / height

        return NormalizedCropRect(
            left = cropLeft.coerceIn(0f, 0.45f),
            top = cropTop.coerceIn(0f, 0.45f),
            right = cropRight.coerceIn(0.55f, 1f),
            bottom = cropBottom.coerceIn(0.55f, 1f)
        )
    }

    suspend fun cropPdf(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        cropRect: NormalizedCropRect,
        targetPageIndex: Int? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("left", cropRect.left.toDouble())
            params.put("top", cropRect.top.toDouble())
            params.put("right", cropRect.right.toDouble())
            params.put("bottom", cropRect.bottom.toDouble())
            if (targetPageIndex != null) {
                params.put("targetPageIndex", targetPageIndex)
            } else {
                params.put("targetPageIndex", JSONObject.NULL)
            }

            val resultStr = PdfGateway.executeEngine(context, "CROP", sourcePdfUri, destPdfUri, params.toString())
            val json = JSONObject(resultStr)
            if (json.has("error")) return@withContext Result.failure(Exception(json.getString("error")))
            
            val success = json.optBoolean("success", false)
            if (success) {
                val historyRepo = com.pdfchemy.app.logic.HistoryRepository(context)
                historyRepo.addHistoryItem(
                    destPdfUri,
                    FileUtils.getFileName(context, destPdfUri) ?: "cropped.pdf",
                    "Cropped PDF"
                )
                Result.success(true)
            } else {
                Result.failure(Exception("Unknown crop failure"))
            }
        } catch (e: Exception) {
            AppLogger.e("PdfCropEngine: Error cropping PDF via gateway", e)
            Result.failure(e)
        }
    }
}

