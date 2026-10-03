package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri

import com.pdfchemy.app.utils.AppLogger
import org.json.JSONObject

object PdfOcrEngine {

    suspend fun createSearchablePdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Boolean {
        return try {
            val contract = PdfGateway.executeEngineTyped<StandardOutputContract>(
                context,
                "OCR_PROCESS",
                sourceUri,
                destUri,
                "{}"
            )
            contract.success
        } catch (e: Exception) {
            AppLogger.e("Failed to create searchable OCR PDF: ${e.message}", e)
            false
        }
    }
}
