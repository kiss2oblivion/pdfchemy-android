package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfTextExtractor {

    suspend fun extractText(context: Context, sourceUri: Uri, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            PdfGateway.executeEngine(context, "TEXT_EXTRACT", sourceUri, destUri, "{}")
            true
        } catch (e: Exception) {
            AppLogger.e("PdfTextExtractor: Error extracting text", e)
            false
        }
    }

    suspend fun extractUsingOcr(context: Context, sourceUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val tempFile = java.io.File.createTempFile("ocr_", ".txt", context.cacheDir)
            val destUri = Uri.fromFile(tempFile)
            val params = org.json.JSONObject().apply { put("forceOcr", true) }.toString()
            PdfGateway.executeEngine(context, "TEXT_EXTRACT", sourceUri, destUri, params)
            if (tempFile.exists()) {
                val text = tempFile.readText()
                tempFile.delete()
                text
            } else {
                ""
            }
        } catch (e: Exception) {
            AppLogger.e("PdfTextExtractor: Error extracting text with OCR", e)
            ""
        }
    }

}
