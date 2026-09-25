package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * 100% Offline & Local-First Auto-Deskew & Scanner Angle Straightener.
 * Detects scan tilt using projection profile variance analysis and corrects skew losslessly.
 */
object PdfDeskewEngine {

    /**
     * Estimates skew angle in degrees (negative = tilted counterclockwise, positive = tilted clockwise).
     */
    fun detectSkewAngle(bitmap: Bitmap): Float {
        val targetWidth = 250
        val scale = targetWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val w = scaled.width
        val h = scaled.height

        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != bitmap) {
            scaled.recycle()
        }

        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = if ((0.299f * r + 0.587f * g + 0.114f * b) < 180f) 1.0f else 0.0f
        }

        val centerX = w / 2.0
        val centerY = h / 2.0
        val rowSums = DoubleArray(h)

        fun computeVarianceForAngle(testAngle: Float): Double {
            val rad = Math.toRadians(testAngle.toDouble())
            val cosA = cos(rad)
            val sinA = sin(rad)
            java.util.Arrays.fill(rowSums, 0.0)

            for (y in 0 until h) {
                val dy = y - centerY
                val yBase = dy * cosA + centerY
                val yOffset = y * w
                for (x in 0 until w step 2) {
                    val dx = x - centerX
                    val rotY = (-dx * sinA + yBase).toInt()
                    if (rotY in 0 until h) {
                        rowSums[rotY] += lum[yOffset + x].toDouble()
                    }
                }
            }

            var sum = 0.0
            var sumSq = 0.0
            for (s in rowSums) {
                sum += s
                sumSq += s * s
            }
            val mean = sum / h.toDouble()
            return (sumSq / h.toDouble()) - (mean * mean)
        }

        var bestAngle = 0.0f
        var maxVariance = -1.0
        var coarseAngle = -15.0f
        while (coarseAngle <= 15.0f) {
            val variance = computeVarianceForAngle(coarseAngle)
            if (variance > maxVariance) {
                maxVariance = variance
                bestAngle = coarseAngle
            }
            coarseAngle += 2.0f
        }

        val fineStart = (bestAngle - 2.0f).coerceAtLeast(-15.0f)
        val fineEnd = (bestAngle + 2.0f).coerceAtMost(15.0f)
        var fineAngle = fineStart
        while (fineAngle <= fineEnd) {
            val variance = computeVarianceForAngle(fineAngle)
            if (variance > maxVariance) {
                maxVariance = variance
                bestAngle = fineAngle
            }
            fineAngle += 0.25f
        }

        return bestAngle
    }

    suspend fun deskewDocument(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        targetPages: Set<Int>? = null
    ): Int {
        val params = JSONObject()
        if (targetPages != null) {
            val arr = org.json.JSONArray()
            targetPages.forEach { arr.put(it) }
            params.put("targetPages", arr)
        }

        val resultStr = PdfGateway.executeEngine(
            context,
            "DESKEW",
            sourceUri,
            destUri,
            params.toString()
        )
        return try {
            JSONObject(resultStr).optInt("straightenedCount", 0)
        } catch (e: Exception) {
            0
        }
    }
}
