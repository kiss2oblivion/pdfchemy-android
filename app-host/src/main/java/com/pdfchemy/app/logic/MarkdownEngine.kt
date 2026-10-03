package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MarkdownEngine {
    suspend fun markdownToPdf(context: Context, markdownText: String, destPdfUri: Uri, documentTitle: String = "Document"): Result<Boolean> = runCatching {
        JailEngineBridge.call(context, "MARKDOWN_TO_PDF", null, destPdfUri, mapOf("text" to markdownText, "title" to documentTitle))
        HistoryRepository(context).addHistoryItem(destPdfUri, com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "document.pdf", "Markdown to PDF")
        true
    }

    suspend fun pdfToMarkdown(context: Context, pdfUri: Uri): Result<String> = runCatching {
        val contract = JailEngineBridge.callTyped<MarkdownContract>(context, "PDF_TO_MARKDOWN", pdfUri, null)
        contract.markdownText
    }
}
