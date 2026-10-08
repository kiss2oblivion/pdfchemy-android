package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri

import com.pdfchemy.app.utils.AppLogger

object PdfOcrEngine {

    suspend fun createSearchablePdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onSaving: () -> Unit = {}
    ): Boolean {
        return try {
            val contract = PdfGateway.executeOcr(context, sourceUri, destUri) { event ->
                if (event.saving) onSaving() else onProgress(event.completed, event.total)
            }
            contract.success
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.e("Failed to create searchable OCR PDF: ${e.message}", e)
            false
        }
    }
}
