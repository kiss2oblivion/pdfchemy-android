package com.pdfchemy.app.sandbox

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.security.StagedIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object JailNativeRendererCoordinator {
    suspend fun getPageCount(pdfPfd: ParcelFileDescriptor, rendererBinder: IBinder?): Int? = withContext(Dispatchers.IO) {
        if (rendererBinder == null) return@withContext null
        val (hash, size) = StagedIdentity.identity(pdfPfd)
        IPdfNativeRendererService.Stub.asInterface(rendererBinder).getPageCount(pdfPfd, hash, size)
    }
    suspend fun renderPageToJpeg(pdfPfd: ParcelFileDescriptor, pageIndex: Int, outputJpegPfd: ParcelFileDescriptor, rendererBinder: IBinder?): Boolean? = withContext(Dispatchers.IO) {
        if (rendererBinder == null) return@withContext null
        val (hash, size) = StagedIdentity.identity(pdfPfd)
        IPdfNativeRendererService.Stub.asInterface(rendererBinder).renderPageToJpeg(pdfPfd, pageIndex, outputJpegPfd, hash, size)
        true
    }
}
