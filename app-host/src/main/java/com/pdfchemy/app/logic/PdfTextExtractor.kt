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
            val tempFile = java.io.File(context.cacheDir, "ocr_temp.txt")
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

    // Temporary shim to fix build errors for unmigrated engines (will be removed in Batch B/C)
    fun extractAllPagesText(document: com.tom_roush.pdfbox.pdmodel.PDDocument): List<String> {
        val totalPages = document.numberOfPages
        if (totalPages == 0) return emptyList()

        val pagesText = ArrayList<String>(totalPages)
        var currentWriter = java.io.StringWriter()

        val stripper = object : com.tom_roush.pdfbox.text.PDFTextStripper() {
            override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                currentWriter = java.io.StringWriter()
                output = currentWriter
            }
            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                output.flush()
                pagesText.add(currentWriter.toString())
            }
        }

        stripper.startPage = 1
        stripper.endPage = totalPages
        try {
            stripper.writeText(document, java.io.StringWriter())
        } catch (e: Exception) {}
        return pagesText
    }
}
