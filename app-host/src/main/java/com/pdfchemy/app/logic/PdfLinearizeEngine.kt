package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class LinearizeStatus(
    val isLinearized: Boolean,
    val fileSizeOriginal: Long,
    val pageCount: Int,
    val verificationStatus: String = "NOT_VERIFIED"
)

object PdfLinearizeEngine {
    suspend fun checkLinearized(context: Context, pdfUri: Uri): Result<LinearizeStatus> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<LinearizeCheckContract>(context, "LINEARIZE_CHECK", pdfUri, null, "{}")
            Result.success(LinearizeStatus(false, FileUtils.getFileSize(context, pdfUri),
                contract.pageCount, contract.verificationStatus))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("PdfLinearizeEngine: Error inspecting PDF", e)
            Result.failure(e)
        }
    }

    suspend fun optimizeFastWebView(context: Context, sourcePdfUri: Uri, destPdfUri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // No Gateway/publication/history call: existing and newly selected destinations stay untouched.
        Result.failure(UnsupportedOperationException("Fast Web View generation is not supported by the local engine"))
    }
}
