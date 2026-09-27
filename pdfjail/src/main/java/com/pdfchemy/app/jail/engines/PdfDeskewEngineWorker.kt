package com.pdfchemy.app.jail.engines

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object PdfDeskewEngineWorker {

    fun deskew(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val targetPagesArray = params.optJSONArray("targetPages")
        val targetPages = if (targetPagesArray != null) {
            val set = mutableSetOf<Int>()
            for (i in 0 until targetPagesArray.length()) {
                set.add(targetPagesArray.getInt(i))
            }
            set
        } else null

        var tempSourceFile: File? = null
        var tempDestFile: File? = null
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        var document: PDDocument? = null
        var straightenedCount = 0

        try {
            tempSourceFile = File.createTempFile("deskew_src", ".pdf")
            FileOutputStream(tempSourceFile).use { out ->
                FileInputStream(sourceFd.fileDescriptor).use { inp ->
                    inp.copyTo(out)
                }
            }

            pfd = ParcelFileDescriptor.open(tempSourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            document = PDDocument.load(tempSourceFile, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())

            val totalPages = document.numberOfPages
            for (i in 0 until totalPages) {
                if (targetPages != null && i !in targetPages) continue

                var page: PdfRenderer.Page? = null
                var bmp: Bitmap? = null
                val angle = try {
                    page = renderer.openPage(i)
                    bmp = Bitmap.createBitmap(250, (250f * page.height / page.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    detectSkewAngle(bmp)
                } catch (e: Exception) {
                    0.0f
                } finally {
                    bmp?.recycle()
                    page?.close()
                }

                if (abs(angle) >= 0.5f) {
                    val pdPage = document.getPage(i)
                    applyDeskewTransform(document, pdPage, -angle)
                    straightenedCount++
                }
            }

            tempDestFile = File.createTempFile("deskew_dest", ".pdf")
            document.save(tempDestFile)
            document.close()
            document = null
            renderer.close()
            renderer = null
            pfd.close()
            pfd = null

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                tempDestFile.inputStream().use { inp ->
                    inp.copyTo(out)
                }
            }

            val result = JSONObject()
            result.put("straightenedCount", straightenedCount)
            return result.toString()
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            tempSourceFile?.delete()
            tempDestFile?.delete()
        }
    }

    private fun detectSkewAngle(bitmap: Bitmap): Float {
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

    private fun applyDeskewTransform(doc: PDDocument, page: PDPage, correctionAngleDegrees: Float) {
        val rad = Math.toRadians(correctionAngleDegrees.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()

        val mb = page.mediaBox
        val cx = mb.width / 2f
        val cy = mb.height / 2f

        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, false, false).use { cs ->
            cs.saveGraphicsState()
            val translateToCenter = PdfMatrix.getTranslateInstance(cx, cy)
            val rotateMatrix = PdfMatrix(cos, sin, -sin, cos, 0f, 0f)
            val translateBack = PdfMatrix.getTranslateInstance(-cx, -cy)

            cs.transform(translateToCenter)
            cs.transform(rotateMatrix)
            cs.transform(translateBack)
        }

        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, false, false).use { cs ->
            cs.restoreGraphicsState()
        }
    }
}
