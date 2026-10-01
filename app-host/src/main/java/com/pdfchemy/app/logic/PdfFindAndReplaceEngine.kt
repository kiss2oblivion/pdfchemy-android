package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import com.pdfchemy.app.security.PixelWire
import com.pdfchemy.app.security.SecurityLimits
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfFindAndReplaceEngine {
    suspend fun findOccurrences(context: Context, pdfUri: Uri, query: String, matchCase: Boolean = false): FindReplaceSummary =
        Gson().fromJson(JailEngineBridge.call(context, "FIND_OCCURRENCES", pdfUri, null, mapOf("query" to query, "matchCase" to matchCase)), FindReplaceSummary::class.java)
    suspend fun replaceAll(context: Context, sourcePdfUri: Uri, destPdfUri: Uri, findText: String, replaceText: String, matchCase: Boolean = false, maskColorRgb: Triple<Float, Float, Float> = Triple(1f, 1f, 1f), textColorRgb: Triple<Float, Float, Float> = Triple(0f, 0f, 0f)): Result<Int> = runCatching {
        val result = JailEngineBridge.call(context, "REPLACE_ALL", sourcePdfUri, destPdfUri, mapOf("findText" to findText, "replaceText" to replaceText, "matchCase" to matchCase, "maskColorRgb" to maskColorRgb, "textColorRgb" to textColorRgb))
        HistoryRepository(context).addHistoryItem(destPdfUri, com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "replaced.pdf", "Find & Replace PDF")
        Gson().fromJson(result, Int::class.java)
    }
}
