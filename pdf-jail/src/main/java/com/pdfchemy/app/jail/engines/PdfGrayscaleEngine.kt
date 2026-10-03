package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.*

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.pdfchemy.app.jail.AppLogger
import com.pdfchemy.app.utils.FileUtils
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfGrayscaleEngine {

    /**
     * Generates a preview bitmap of a specific page converted to Grayscale or Monochrome.
     */
    suspend fun generatePreview(
        context: Context,
        pdfUri: Uri,
        pageIndex: Int = 0,
        mode: GrayscaleMode = GrayscaleMode.GRAYSCALE_8BIT,
        threshold: Int = 128
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = com.pdfchemy.app.jail.CapabilityIo.descriptor(context, pdfUri, "r")
                ?: throw IllegalStateException("Cannot open PDF file")

            renderer = PdfRenderer(pfd)
            if (pageIndex !in 0 until renderer.pageCount) {
                return@withContext Result.failure(IllegalArgumentException("Invalid page index $pageIndex"))
            }

            val page = renderer.openPage(pageIndex)
            val scale = 1.5f
            val (width, height) = com.pdfchemy.app.security.SecurityLimits.safeRenderSize(page.width.toDouble(), page.height.toDouble(), scale.toDouble())

            val srcBitmap = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(width, height); Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            try {
                val canvas = Canvas(srcBitmap)
                canvas.drawColor(Color.WHITE)
                page.render(srcBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }

            val processedBitmap = applyFilter(srcBitmap, mode, threshold)
            if (processedBitmap != srcBitmap) {
                srcBitmap.recycle()
            }
            Result.success(processedBitmap)
        } catch (e: Exception) {
            AppLogger.e("PdfGrayscaleEngine: Error generating preview", e)
            Result.failure(e)
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Converts a complete PDF into Grayscale or High-Contrast Monochrome.
     */
    suspend fun convertPdf(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        mode: GrayscaleMode = GrayscaleMode.GRAYSCALE_8BIT,
        threshold: Int = 128,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        var outDoc: PDDocument? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var tempFile: File? = null

        try {
            pfd = com.pdfchemy.app.jail.CapabilityIo.descriptor(context, sourcePdfUri, "r")
                ?: throw IllegalStateException("Cannot open source PDF")

            renderer = PdfRenderer(pfd)
            val totalPages = renderer.pageCount
            if (totalPages == 0) {
                return@withContext Result.failure(IllegalStateException("PDF contains no pages"))
            }

            outDoc = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())

            for (i in 0 until totalPages) {
                val page = renderer.openPage(i)
                val scale = 2f
                val (bmpW, bmpH) = com.pdfchemy.app.security.SecurityLimits.safeRenderSize(page.width.toDouble(), page.height.toDouble(), scale.toDouble())

                val rawBitmap = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(bmpW, bmpH); Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888) }
                try {
                    val canvas = Canvas(rawBitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(rawBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                } finally {
                    page.close()
                }

                val processedBitmap = applyFilter(rawBitmap, mode, threshold)

                val pageRect = PDRectangle(page.width.toFloat(), page.height.toFloat())
                val outPage = PDPage(pageRect)
                outDoc.addPage(outPage)

                val pdImage = JPEGFactory.createFromImage(outDoc, processedBitmap, 0.88f)
                PDPageContentStream(outDoc, outPage).use { cs ->
                    cs.drawImage(pdImage, 0f, 0f, page.width.toFloat(), page.height.toFloat())
                }

                if (processedBitmap != rawBitmap) {
                    rawBitmap.recycle()
                }
                processedBitmap.recycle()

                onProgress(i + 1, totalPages)
            }

            renderer.close()
            renderer = null
            pfd.close()
            pfd = null

            tempFile = com.pdfchemy.app.jail.JailScratch.namedFile("gray_${System.currentTimeMillis()}.pdf")
            com.pdfchemy.app.jail.boundedFileOutput(tempFile).use { outDoc.save(it) }
            outDoc.close()
            outDoc = null

            com.pdfchemy.app.jail.CapabilityIo.output(context, destPdfUri)?.let { com.pdfchemy.app.security.BoundedOutputStream(it) }?.use { out ->
                com.pdfchemy.app.jail.CapabilityIo.input(tempFile).use { inp ->
                    inp.copyTo(out)
                }
            } ?: throw IllegalStateException("Cannot open destination PDF stream")

            tempFile.delete()
            tempFile = null


            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfGrayscaleEngine: Error converting PDF", e)
            Result.failure(e)
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
            try { outDoc?.close() } catch (_: Throwable) {}
            tempFile?.delete()
        }
    }

    private fun applyFilter(bitmap: Bitmap, mode: GrayscaleMode, threshold: Int): Bitmap {
        return when (mode) {
            GrayscaleMode.GRAYSCALE_8BIT -> {
                val grayBitmap = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(bitmap.width, bitmap.height); Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888) }
                val canvas = Canvas(grayBitmap)
                val paint = Paint()
                val colorMatrix = ColorMatrix().apply { setSaturation(0f) }
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                grayBitmap
            }
            GrayscaleMode.MONOCHROME_BINARY -> {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                for (i in pixels.indices) {
                    val p = pixels[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    // Luminance formula (ITU-R BT.601)
                    val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
                    val binary = if (gray > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                    pixels[i] = binary
                }

                val monoBitmap = run { com.pdfchemy.app.security.SecurityLimits.requirePixels(width, height); Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                monoBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                monoBitmap
            }
        }
    }
}
