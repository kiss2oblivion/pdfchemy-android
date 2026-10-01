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

object MarkdownEngine {
    suspend fun markdownToPdf(context: Context, markdownText: String, destPdfUri: Uri, documentTitle: String = "Document"): Result<Boolean> = runCatching {
        JailEngineBridge.call(context, "MARKDOWN_TO_PDF", null, destPdfUri, mapOf("text" to markdownText, "title" to documentTitle))
        HistoryRepository(context).addHistoryItem(destPdfUri, com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "document.pdf", "Markdown to PDF")
        true
    }
    suspend fun pdfToMarkdown(context: Context, pdfUri: Uri): Result<String> = runCatching {
        Gson().fromJson(JailEngineBridge.call(context, "PDF_TO_MARKDOWN", pdfUri, null), String::class.java)
    }
}
