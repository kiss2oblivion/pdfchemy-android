package com.pdfchemy.app.sandbox

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.FileOutputStream

/**
 * Isolated process service strictly for executing native PdfRenderer operations.
 * Android's PdfRenderer uses Skia, which is a known attack vector for malicious PDFs.
 * By running this in an isolated process, a zero-day in Skia cannot compromise the app's UID.
 */
class PdfNativeRendererService : Service() {

    private val binder = object : IPdfNativeRendererService.Stub() {
        override fun getWorkerPid(): Int = Process.myPid()

        override fun getPageCount(pdfPfd: ParcelFileDescriptor): Int {
            var renderer: PdfRenderer? = null
            try {
                renderer = PdfRenderer(pdfPfd)
                return renderer.pageCount
            } catch (e: Exception) {
                Log.e("PdfNativeRenderer", "PdfNativeRenderer: getPageCount failed", e)
                throw e
            } finally {
                try { renderer?.close() } catch (_: Exception) {}
            }
        }

        override fun renderPageToJpeg(pdfPfd: ParcelFileDescriptor, pageIndex: Int, outputJpegPfd: ParcelFileDescriptor) {
            var renderer: PdfRenderer? = null
            var renderPage: PdfRenderer.Page? = null
            var bmp: Bitmap? = null
            try {
                renderer = PdfRenderer(pdfPfd)
                renderPage = renderer.openPage(pageIndex)
                
                val maxDim = 2048
                val scale = minOf(2f, maxDim.toFloat() / maxOf(renderPage.width, renderPage.height).coerceAtLeast(1))
                val targetW = (renderPage.width * scale).toInt().coerceAtLeast(1)
                val targetH = (renderPage.height * scale).toInt().coerceAtLeast(1)
                
                bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                
                renderPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                
                FileOutputStream(outputJpegPfd.fileDescriptor).use { outStream ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, outStream)
                }
            } catch (e: Exception) {
                Log.e("PdfNativeRenderer", "PdfNativeRenderer: renderPageToJpeg failed", e)
                throw e
            } finally {
                bmp?.recycle()
                try { renderPage?.close() } catch (_: Exception) {}
                try { renderer?.close() } catch (_: Exception) {}
                try { outputJpegPfd.close() } catch (_: Exception) {} // Close pipe on our side
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
