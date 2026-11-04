package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.pdfchemy.app.security.SecurityLimits
import java.io.File

object PdfTextExtractor {

    suspend fun extractText(context: Context, sourceUri: Uri, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            PdfGateway.executeEngine(context, "TEXT_EXTRACT", sourceUri, destUri, "{}")
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.e("PdfTextExtractor: Error extracting text", e)
            false
        }
    }

    suspend fun extractUsingOcr(context: Context, sourceUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        var temporary: File? = null
        try {
            val tempFile = File.createTempFile("ocr_", ".txt", context.cacheDir).also { temporary = it }
            val destUri = Uri.fromFile(tempFile)
            val params = org.json.JSONObject().apply { put("forceOcr", true) }.toString()
            PdfGateway.executeEngine(context, "TEXT_EXTRACT", sourceUri, destUri, params)
            require(tempFile.length() <= SecurityLimits.MAX_TEXT_BYTES) { "OCR text exceeds size limit" }
            val text = tempFile.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                buildString {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        require(length <= SecurityLimits.MAX_TEXT_BYTES - count) { "OCR text exceeds size limit" }
                        append(buffer, 0, count)
                    }
                }
            }
            SecurityLimits.enforceStringLength(text)
            Result.success(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.e("PdfTextExtractor: Error extracting text with OCR", e)
            Result.failure(e)
        } finally {
            temporary?.let { if (it.exists() && !it.delete()) AppLogger.w("Unable to remove temporary OCR output") }
        }
    }

}
