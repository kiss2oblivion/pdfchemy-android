package com.pdfchemy.app.sandbox

import android.content.*
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.security.*
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

object NativeRendererCoordinator {
    private suspend fun <T> withRenderer(context: Context, block: (IPdfNativeRendererService) -> T): T = withContext(Dispatchers.IO) {
        val channel = Channel<IPdfNativeRendererService>(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { channel.trySend(IPdfNativeRendererService.Stub.asInterface(service)) }
            override fun onServiceDisconnected(name: ComponentName?) { channel.close(IllegalStateException("Renderer died")) }
            override fun onNullBinding(name: ComponentName?) { channel.close(IllegalStateException("Null renderer")) }
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.sandbox.PdfNativeRendererService"), connection, Context.BIND_AUTO_CREATE))
        try { block(withTimeout(10_000) { channel.receive() }) }
        finally { context.unbindService(connection) }
    }
    suspend fun renderUriToBitmap(context: Context, uri: Uri, pageIndex: Int, width: Int): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val staged = DocumentStager.stageDocumentCancellable(context, uri)
        val inputLease = DocumentStager.retain(staged)
        var file: File? = null
        try {
            val pixels = File.createTempFile("pixels_", ".argb", context.cacheDir)
            file = pixels
            context.contentResolver.openFileDescriptor(staged.uri, "r")!!.use { source ->
                ParcelFileDescriptor.open(pixels, ParcelFileDescriptor.MODE_READ_WRITE).use { dest ->
                    withRenderer(context) { it.renderPageToPixels(source, pageIndex, dest, staged.sha256, staged.size, width) }
                }
            }
            pixels.inputStream().use(PixelWire::read)
        } finally {
            file?.delete()
            if (staged.uri != uri) DocumentStager.release(staged)
            inputLease.close()
        }
    }
    suspend fun getPageCount(context: Context, pdfPfd: ParcelFileDescriptor, rendererBinder: IBinder? = null): Int? = withContext(Dispatchers.IO) {
        val (hash, size) = StagedIdentity.identity(pdfPfd)
        if (rendererBinder != null) IPdfNativeRendererService.Stub.asInterface(rendererBinder).getPageCount(pdfPfd, hash, size)
        else withRenderer(context) { it.getPageCount(pdfPfd, hash, size) }
    }
    suspend fun renderPageToBitmap(context: Context, pdfPfd: ParcelFileDescriptor, pageIndex: Int, scaleWidth: Int = 0): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val (hash, size) = StagedIdentity.identity(pdfPfd)
        val file = File.createTempFile("pixels_", ".argb", context.cacheDir)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                withRenderer(context) { it.renderPageToPixels(pdfPfd, pageIndex, target, hash, size, if (scaleWidth > 0) scaleWidth else SecurityLimits.MAX_RENDER_DIMENSION) }
            }
            file.inputStream().use(PixelWire::read)
        } finally { file.delete() }
    }
    suspend fun renderPageToJpeg(context: Context, pdfPfd: ParcelFileDescriptor, pageIndex: Int, outputJpegPfd: ParcelFileDescriptor, rendererBinder: IBinder? = null): Boolean? = withContext(Dispatchers.IO) {
        val (hash, size) = StagedIdentity.identity(pdfPfd)
        if (rendererBinder != null) IPdfNativeRendererService.Stub.asInterface(rendererBinder).renderPageToJpeg(pdfPfd, pageIndex, outputJpegPfd, hash, size)
        else withRenderer(context) { it.renderPageToJpeg(pdfPfd, pageIndex, outputJpegPfd, hash, size) }
        true
    }
}
