package com.pdfchemy.app.sandbox
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.pdfchemy.app.jail.engines.NativeRenderWorker

class PdfNativeRendererService : Service() {
    private val worker = NativeRenderWorker()
    private val binder = object : IPdfNativeRendererService.Stub() {
        override fun getWorkerPid() = Process.myPid()
        override fun getWorkerUid() = Process.myUid()
        override fun debugBlock(pdfPfd: ParcelFileDescriptor, sha256: String, size: Long) = worker.debugBlock(pdfPfd, sha256, size)
        override fun abortWorker() { Process.killProcess(Process.myPid()) }
        override fun getPageCount(pdfPfd: ParcelFileDescriptor, sha256: String, size: Long): Int = worker.getPageCount(pdfPfd, sha256, size)
        override fun renderPageToJpeg(pdfPfd: ParcelFileDescriptor, pageIndex: Int, outputJpegPfd: ParcelFileDescriptor, sha256: String, size: Long) = worker.jpeg(pdfPfd, pageIndex, outputJpegPfd, sha256, size)
        override fun renderPageToPixels(pdfPfd: ParcelFileDescriptor, pageIndex: Int, outputPfd: ParcelFileDescriptor, sha256: String, size: Long, maxWidth: Int) = worker.pixels(pdfPfd, pageIndex, outputPfd, sha256, size, maxWidth)
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { worker.close(); super.onDestroy() }
}
