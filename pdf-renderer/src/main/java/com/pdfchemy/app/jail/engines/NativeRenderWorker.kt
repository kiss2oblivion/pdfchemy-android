package com.pdfchemy.app.jail.engines
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Process
import com.pdfchemy.app.security.*
import java.io.FileOutputStream

class NativeRenderWorker : java.io.Closeable {
    private val gate = WorkerGate { Process.killProcess(Process.myPid()) }
    private fun <T> execute(pdf: ParcelFileDescriptor, output: ParcelFileDescriptor?, hash: String, size: Long, block: (PdfRenderer) -> T): T {
        try {
            val lease = gate.acquire(SecurityLimits.RENDER_DEADLINE_MS) ?: throw IllegalStateException("BUSY")
            lease.use {
                StagedIdentity.verifyAndRewind(pdf, hash, size)
                return PdfRenderer(pdf).use(block)
            }
        } catch (e: Exception) {
            output?.let { runCatching { android.system.Os.ftruncate(it.fileDescriptor, 0) } }
            throw e
        } finally { runCatching { pdf.close() }; runCatching { output?.close() } }
    }
    private fun render(renderer: PdfRenderer, index: Int, maxWidth: Int, output: ParcelFileDescriptor, raw: Boolean) {
        require(index in 0 until renderer.pageCount)
        renderer.openPage(index).use { page ->
            val requestedScale = minOf(2.0, maxWidth.coerceIn(1, SecurityLimits.MAX_RENDER_DIMENSION).toDouble() / page.width)
            val (w, h) = SecurityLimits.safeRenderSize(page.width.toDouble(), page.height.toDouble(), requestedScale)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                FileOutputStream(output.fileDescriptor).use { stream ->
                    val bounded = BoundedOutputStream(stream)
                    if (raw) PixelWire.write(bitmap, bounded)
                    else check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bounded))
                }
            } finally { bitmap.recycle() }
        }
    }

    fun getPageCount(pdf: ParcelFileDescriptor, hash: String, size: Long) = execute(pdf, null, hash, size) { it.pageCount }
    fun debugBlock(pdf: ParcelFileDescriptor, hash: String, size: Long) {
        execute(pdf, null, hash, size) {
            check(com.pdfchemy.nativerenderer.BuildConfig.DEBUG)
            while (true) Thread.sleep(1000)
        }
    }
    fun jpeg(pdf: ParcelFileDescriptor, index: Int, output: ParcelFileDescriptor, hash: String, size: Long) { execute(pdf, output, hash, size) { render(it, index, SecurityLimits.MAX_RENDER_DIMENSION, output, false) } }
    fun pixels(pdf: ParcelFileDescriptor, index: Int, output: ParcelFileDescriptor, hash: String, size: Long, width: Int) { execute(pdf, output, hash, size) { render(it, index, width, output, true) } }
    override fun close() = gate.close()
}
