package com.pdfchemy.app.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object NativeRendererCoordinator {

    private const val HARD_TIMEOUT_MS = 30_000L // 30 seconds per page render

    private suspend fun <T> withRenderer(context: Context, block: suspend (IPdfNativeRendererService) -> T): T? = withContext(Dispatchers.IO) {
        val channel = Channel<IPdfNativeRendererService?>()
        var workerPid = -1
        
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val renderer = IPdfNativeRendererService.Stub.asInterface(service)
                try {
                    workerPid = renderer.workerPid
                } catch (e: Exception) {
                    AppLogger.e("NativeRendererCoordinator: Failed to get PID", e)
                }
                channel.trySend(renderer)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                AppLogger.w("NativeRendererCoordinator: Service disconnected unexpectedly.")
                channel.trySend(null)
            }
        }

        val intent = Intent(context, PdfNativeRendererService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (!bound) {
            AppLogger.e("NativeRendererCoordinator: Failed to bind to PdfNativeRendererService")
            return@withContext null
        }

        var result: T? = null
        try {
            val renderer = channel.receive()
            if (renderer != null) {
                result = withTimeoutOrNull(HARD_TIMEOUT_MS) {
                    block(renderer)
                }
                if (result == null && workerPid != -1) {
                    AppLogger.e("NativeRendererCoordinator: Native render timed out. Killing native worker PID $workerPid")
                    Process.killProcess(workerPid)
                }
            }
        } catch (e: Exception) {
            AppLogger.e("NativeRendererCoordinator: Error communicating with native renderer", e)
            if (workerPid != -1) {
                Process.killProcess(workerPid)
            }
        } finally {
            context.unbindService(connection)
        }
        
        result
    }

    suspend fun getPageCount(context: Context, pdfPfd: ParcelFileDescriptor): Int? = withRenderer(context) { renderer ->
        renderer.getPageCount(pdfPfd)
    }

    suspend fun renderPageToBitmap(context: Context, pdfPfd: ParcelFileDescriptor, pageIndex: Int, scaleWidth: Int = 0): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        withRenderer(context) { renderer ->
            val tempFile = java.io.File(context.cacheDir, "thumb_.jpg")
            var jpegPfd: ParcelFileDescriptor? = null
            var bitmap: android.graphics.Bitmap? = null
            try {
                jpegPfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
                renderer.renderPageToJpeg(pdfPfd, pageIndex, jpegPfd)
                jpegPfd.close()
                if (tempFile.exists() && tempFile.length() > 0) {
                    val original = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
                    if (original != null) {
                        if (scaleWidth > 0 && original.width > scaleWidth) {
                            val scale = scaleWidth.toFloat() / original.width
                            val h = (original.height * scale).toInt()
                            bitmap = android.graphics.Bitmap.createScaledBitmap(original, scaleWidth, h, true)
                            original.recycle()
                        } else {
                            bitmap = original
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("NativeRendererCoordinator: Failed to render bitmap", e)
            } finally {
                try { jpegPfd?.close() } catch (e: Exception) {}
                tempFile.delete()
            }
            bitmap
        }
    }

    suspend fun renderPageToJpeg(context: Context, pdfPfd: ParcelFileDescriptor, pageIndex: Int, outputJpegPfd: ParcelFileDescriptor): Boolean? = withRenderer(context) { renderer ->
        renderer.renderPageToJpeg(pdfPfd, pageIndex, outputJpegPfd)
        true
    }
}
