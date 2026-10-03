package com.pdfchemy.app.jail.engines

import android.graphics.Bitmap
import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

object SafePdfImage {
    fun decode(image: PDImageXObject): Bitmap {
        // Some PDFBox filters allocate a full intermediate raster despite subsampling.
        SecurityLimits.requirePixels(image.width, image.height)
        image.mask?.let { SecurityLimits.requirePixels(it.width, it.height) }
        image.softMask?.let { SecurityLimits.requirePixels(it.width, it.height) }
        val bitmap = requireNotNull(image.image)
        try { SecurityLimits.requirePixels(bitmap.width, bitmap.height) }
        catch (error: Exception) { bitmap.recycle(); throw error }
        return bitmap
    }
}
