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
            val jsonResult = PdfGateway.executeEngine(context, "LINEARIZE_CHECK", pdfUri, null, "{}")
            val obj = JSONObject(jsonResult)
            val size = FileUtils.getFileSize(context, pdfUri)
            Result.success(LinearizeStatus(
                isLinearized = obj.getBoolean("isLinearized"),
                fileSizeOriginal = size,
                pageCount = obj.getInt("pageCount")
            ))
        } catch (e: Exception) {
            AppLogger.e("PdfLinearizeEngine: Error checking linearization", e)
            Result.failure(e)
        }
    }

    suspend fun optimizeFastWebView(context: Context, sourcePdfUri: Uri, destPdfUri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val jsonResult = PdfGateway.executeEngine(context, "LINEARIZE_OPTIMIZE", sourcePdfUri, destPdfUri, "{}")
            val obj = JSONObject(jsonResult)
            
            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(destPdfUri, FileUtils.getFileName(context, destPdfUri) ?: "web_optimized.pdf", "Fast Web View Stream Optimizer")
            
            Result.success(obj.getLong("outBytes"))
        } catch (e: Exception) {
            AppLogger.e("PdfLinearizeEngine: Error optimizing stream", e)
            Result.failure(e)
        }
    }
}
