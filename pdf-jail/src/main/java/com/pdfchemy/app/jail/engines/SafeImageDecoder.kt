package com.pdfchemy.app.jail.engines

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.security.SecurityLimits
import android.system.Os
import android.system.OsConstants

object SafeImageDecoder {
    fun decode(fd: ParcelFileDescriptor): Bitmap {
        require(fd.statSize in 1..SecurityLimits.MAX_PDF_FILESIZE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (bounds.outWidth / sample > SecurityLimits.MAX_RENDER_DIMENSION || bounds.outHeight / sample > SecurityLimits.MAX_RENDER_DIMENSION || bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > SecurityLimits.MAX_RENDER_PIXELS) sample *= 2
        Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, options) ?: error("Invalid image")
        try { SecurityLimits.requirePixels(bitmap.width, bitmap.height) } catch (e: Exception) { bitmap.recycle(); throw e }
        return bitmap
    }
}
