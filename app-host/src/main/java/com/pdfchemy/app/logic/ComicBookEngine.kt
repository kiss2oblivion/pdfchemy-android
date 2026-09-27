package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object ComicBookEngine {

    suspend fun convertPdfToCbz(context: Context, sourcePdfUri: Uri, destCbzUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("action", "pdf2cbz")
            
            val resultStr = PdfGateway.executeEngine(context, "COMIC_BOOK", sourcePdfUri, destCbzUri, params.toString())
            val json = JSONObject(resultStr)
            
            if (json.has("error")) return@withContext Result.failure(Exception(json.getString("error")))
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("ComicBookEngine", e)
            Result.failure(e)
        }
    }

    suspend fun convertCbzToPdf(context: Context, sourceCbzUri: Uri, destPdfUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("action", "cbz2pdf")
            
            val resultStr = PdfGateway.executeEngine(context, "COMIC_BOOK", sourceCbzUri, destPdfUri, params.toString())
            val json = JSONObject(resultStr)
            
            if (json.has("error")) return@withContext Result.failure(Exception(json.getString("error")))
            Result.success(Unit)
        } catch (e: Exception) {
            AppLogger.e("ComicBookEngine", e)
            Result.failure(e)
        }
    }
}

