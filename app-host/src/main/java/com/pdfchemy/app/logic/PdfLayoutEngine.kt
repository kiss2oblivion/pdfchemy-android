package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import com.pdfchemy.app.security.PixelWire
import com.pdfchemy.app.security.SecurityLimits
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfLayoutEngine {
    suspend fun resizePages(context: Context, sourceUri: Uri, destUri: Uri, targetSize: TargetPaperSize): Boolean =
        runCatching { JailEngineBridge.call(context, "LAYOUT_RESIZE", sourceUri, destUri, mapOf("size" to targetSize.name)); true }.getOrDefault(false)
    suspend fun createNUpLayout(context: Context, sourceUri: Uri, destUri: Uri, mode: NUpMode, targetSize: TargetPaperSize = TargetPaperSize.A4, drawBorders: Boolean = true): Boolean =
        runCatching { JailEngineBridge.call(context, "LAYOUT_NUP", sourceUri, destUri, mapOf("mode" to mode.name, "size" to targetSize.name, "drawBorders" to drawBorders)); true }.getOrDefault(false)
}
