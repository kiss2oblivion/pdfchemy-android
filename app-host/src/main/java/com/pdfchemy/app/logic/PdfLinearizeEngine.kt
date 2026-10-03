package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LinearizeStatus(
    val isLinearized: Boolean,
    val fileSizeOriginal: Long,
    val pageCount: Int
)

object PdfLinearizeEngine {

    suspend fun checkLinearized(context: Context, pdfUri: Uri): Result<LinearizeStatus> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<LinearizeCheckContract>(context, "LINEARIZE_CHECK", pdfUri, null, "{}")
            val size = FileUtils.getFileSize(context, pdfUri)
            Result.success(LinearizeStatus(
                isLinearized = contract.isLinearized,
                fileSizeOriginal = size,
                pageCount = contract.pageCount
            ))
        } catch (e: Exception) {
            AppLogger.e("PdfLinearizeEngine: Error checking linearization", e)
            Result.failure(e)
        }
    }

    suspend fun optimizeFastWebView(context: Context, sourcePdfUri: Uri, destPdfUri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<StandardOutputContract>(context, "LINEARIZE_OPTIMIZE", sourcePdfUri, destPdfUri, "{}")
            
            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(destPdfUri, FileUtils.getFileName(context, destPdfUri) ?: "web_optimized.pdf", "Fast Web View Stream Optimizer")
            
            Result.success(contract.size)
        } catch (e: Exception) {
            AppLogger.e("PdfLinearizeEngine: Error optimizing stream", e)
            Result.failure(e)
        }
    }
}
