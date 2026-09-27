package com.pdfchemy.app.sandbox

import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object JailNativeRendererCoordinator {

    suspend fun getPageCount(pdfPfd: ParcelFileDescriptor, rendererBinder: IBinder?): Int? = withContext(Dispatchers.IO) {
        if (rendererBinder == null) return@withContext null
        val renderer = IPdfNativeRendererService.Stub.asInterface(rendererBinder)
        try { 
            renderer.getPageCount(pdfPfd) 
        } catch(e: Exception) { 
            null 
        }
    }

    suspend fun renderPageToJpeg(pdfPfd: ParcelFileDescriptor, pageIndex: Int, outputJpegPfd: ParcelFileDescriptor, rendererBinder: IBinder?): Boolean? = withContext(Dispatchers.IO) {
        if (rendererBinder == null) return@withContext null
        val renderer = IPdfNativeRendererService.Stub.asInterface(rendererBinder)
        try {
            renderer.renderPageToJpeg(pdfPfd, pageIndex, outputJpegPfd)
            true
        } catch(e: Exception) {
            null
        }
    }
}
