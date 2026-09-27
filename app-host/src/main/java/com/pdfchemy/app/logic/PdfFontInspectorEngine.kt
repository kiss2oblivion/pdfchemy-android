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
            val jsonResult = PdfGateway.executeEngine(context, "FONT_INSPECT", pdfUri, null, "{}")
            val arr = JSONArray(jsonResult)
            val result = mutableListOf<PdfFontInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(PdfFontInfo(
                    postscriptName = obj.getString("postscriptName"),
                    familyName = obj.getString("familyName"),
                    formatType = obj.getString("formatType"),
                    isEmbedded = obj.getBoolean("isEmbedded"),
                    isSubset = obj.getBoolean("isSubset"),
                    encoding = obj.getString("encoding"),
                    pageCountUsed = obj.getInt("pageCountUsed")
                ))
            }
            Result.success(result)
        } catch (e: Exception) {
            AppLogger.e("PdfFontInspectorEngine: Error inspecting fonts", e)
            Result.failure(e)
        }
    }
}
