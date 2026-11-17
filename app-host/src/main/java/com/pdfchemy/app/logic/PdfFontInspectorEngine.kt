package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class PdfFontInfo(
    val postscriptName: String,
    val familyName: String,
    val formatType: String,
    val isEmbedded: Boolean,
    val isSubset: Boolean,
    val encoding: String,
    val pageCountUsed: Int
)

object PdfFontInspectorEngine {

    suspend fun inspectFonts(context: Context, pdfUri: Uri): Result<List<PdfFontInfo>> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<FontInspectContract>(context, "FONT_INSPECT", pdfUri, null, "{}")
            val result = contract.fonts.map {
                PdfFontInfo(
                    postscriptName = it.postscriptName,
                    familyName = it.familyName,
                    formatType = it.formatType,
                    isEmbedded = it.isEmbedded,
                    isSubset = it.isSubset,
                    encoding = it.encoding,
                    pageCountUsed = it.pageCountUsed
                )
            }
            Result.success(result)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.e("PdfFontInspectorEngine: Error inspecting fonts", e)
            Result.failure(e)
        }
    }

    suspend fun extractEmbeddedFonts(context: Context, sourcePdfUri: Uri, destZipUri: Uri): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            PdfGateway.executeEngine(context, "FONT_EXTRACT", sourcePdfUri, destZipUri, "{}")
            Result.success(true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.e("PdfFontInspectorEngine: Error extracting fonts", e)
            Result.failure(e)
        }
    }
}
