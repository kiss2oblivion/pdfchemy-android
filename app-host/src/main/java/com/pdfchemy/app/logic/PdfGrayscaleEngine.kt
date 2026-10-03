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

object PdfGrayscaleEngine {
    suspend fun generatePreview(context: Context, pdfUri: Uri, pageIndex: Int = 0, mode: GrayscaleMode = GrayscaleMode.GRAYSCALE_8BIT, threshold: Int = 128): Result<Bitmap> = runCatching {
        JailEngineBridge.bitmap(context, "GRAYSCALE_PREVIEW", pdfUri, mapOf("pageIndex" to pageIndex, "mode" to mode.name, "threshold" to threshold)) ?: error("No preview")
    }
    suspend fun convertPdf(context: Context, sourcePdfUri: Uri, destPdfUri: Uri, mode: GrayscaleMode = GrayscaleMode.GRAYSCALE_8BIT, threshold: Int = 128, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<Boolean> = runCatching {
        JailEngineBridge.call(context, "GRAYSCALE_CONVERT", sourcePdfUri, destPdfUri, mapOf("mode" to mode.name, "threshold" to threshold))
        HistoryRepository(context).addHistoryItem(destPdfUri, com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "grayscale.pdf", "Grayscale PDF")
        onProgress(1, 1); true
    }
}
