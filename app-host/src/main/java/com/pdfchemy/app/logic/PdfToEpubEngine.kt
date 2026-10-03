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

object PdfToEpubEngine {
    suspend fun pdfToEpub(context: Context, inputStream: java.io.InputStream, outputStream: java.io.OutputStream, bookTitle: String = "Untitled E-Book", authorName: String = "Unknown Author"): Result<Boolean> = runCatching {
        JailEngineBridge.streams(context, "PDF_TO_EPUB", inputStream, outputStream, mapOf("bookTitle" to bookTitle, "authorName" to authorName))
    }
    suspend fun epubToPdf(context: Context, inputStream: java.io.InputStream, outputStream: java.io.OutputStream, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<Boolean> = runCatching {
        JailEngineBridge.streams(context, "EPUB_TO_PDF", inputStream, outputStream, emptyMap<String, String>()).also { onProgress(1, 1) }
    }
}
