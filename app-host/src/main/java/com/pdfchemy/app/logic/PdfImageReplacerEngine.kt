package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import com.pdfchemy.app.security.PixelWire
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfImageReplacerEngine {
    suspend fun listEmbeddedImages(context: Context, pdfUri: Uri): List<EmbeddedImageInfo> {
        val file = File.createTempFile("image_list_", ".argb", context.cacheDir)
        try {
            val contract = JailEngineBridge.callTyped<ImageListContract>(context, "IMAGE_LIST", pdfUri, Uri.fromFile(file))
            val entries = contract.images
            return file.inputStream().buffered().use { stream -> entries.map { it.copy(thumbnailBitmap = PixelWire.read(stream)) } }
        } finally { file.delete() }
    }
    suspend fun replaceEmbeddedImage(context: Context, sourcePdfUri: Uri, destPdfUri: Uri, pageIndex: Int, resourceName: String, replacementBitmap: Bitmap, isLossless: Boolean = true): Result<Boolean> = runCatching {
        SecurityLimits.requirePixels(replacementBitmap.width, replacementBitmap.height)
        val file = File.createTempFile("replacement_", ".argb", context.cacheDir)
        try {
            file.outputStream().use { PixelWire.write(replacementBitmap, it) }
            JailEngineBridge.call(context, "IMAGE_REPLACE", sourcePdfUri, destPdfUri, mapOf("pageIndex" to pageIndex, "resourceName" to resourceName, "isLossless" to isLossless), Uri.fromFile(file))
            HistoryRepository(context).addHistoryItem(destPdfUri, com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "image_swapped.pdf", "Image Replaced PDF")
            true
        } finally { file.delete() }
    }
}
