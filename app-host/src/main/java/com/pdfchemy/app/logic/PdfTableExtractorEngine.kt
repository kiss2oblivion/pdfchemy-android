package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri

import org.json.JSONObject
import java.io.File

/**
 * 100% Offline & Local-First PDF Table & Data Extractor.
 * Extracts tabular data, forms, and spreadsheets from PDF documents directly to RFC 4180 CSV
 * using spatial 2D text clustering without lossy cloud dependencies.
 */
object PdfTableExtractorEngine {

    suspend fun extractTablesToCsv(context: Context, sourceUri: Uri, pageIndex: Int? = null, safeMode: Boolean = false): String {
        val tempFile = File(context.cacheDir, "temp_table_extract_${System.currentTimeMillis()}.csv")
        try {
            val destUri = Uri.fromFile(tempFile)
            val success = extractTablesToCsvFile(context, sourceUri, destUri, pageIndex, safeMode)
            if (success && tempFile.exists()) {
                return com.pdfchemy.app.jail.WorkerResponseValidator.parseCsvTableFile(tempFile)
            }
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
        return ""
    }

    suspend fun extractTablesToCsvFile(context: Context, sourceUri: Uri, destUri: Uri, pageIndex: Int? = null, safeMode: Boolean = false): Boolean {
        val params = JSONObject()
        params.put("safeMode", safeMode)
        if (pageIndex != null) {
            params.put("pageIndex", pageIndex)
        }

        return try {
            val contract = PdfGateway.executeEngineTyped<StandardOutputContract>(
                context,
                "TABLE_EXTRACT",
                sourceUri,
                destUri,
                params.toString()
            )
            contract.success
        } catch (e: Exception) {
            false
        }
    }
}
