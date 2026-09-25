package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

data class OutlineBookmark(
    val title: String,
    val pageNumber: Int,
    val children: List<OutlineBookmark> = emptyList()
)

data class ReflowSection(
    val pageNumber: Int,
    val title: String?,
    val paragraphs: List<String>
)

data class ReflowDocumentData(
    val sections: List<ReflowSection>,
    val bookmarks: List<OutlineBookmark>,
    val isScannedOnly: Boolean = false,
    val totalPages: Int = 0
)

object PdfOutlineReader {

    private fun isEpub(context: Context, uri: Uri): Boolean {
        val name = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri) ?: uri.lastPathSegment ?: ""
        if (name.endsWith(".epub", ignoreCase = true)) return true
        val type = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        return type?.contains("epub", ignoreCase = true) == true
    }

    suspend fun loadReflowDocument(context: Context, sourceUri: Uri): ReflowDocumentData = withContext(Dispatchers.IO) {
        try {
            val isEpubDoc = isEpub(context, sourceUri)
            val params = JSONObject().apply {
                put("isEpub", isEpubDoc)
            }.toString()
            
            val tempFile = java.io.File.createTempFile("outline_", ".json", context.cacheDir)
            val destUri = Uri.fromFile(tempFile)
            PdfGateway.executeEngine(context, "OUTLINE_READ", sourceUri, destUri, params)
            
            val jsonResult = if (tempFile.exists()) tempFile.readText() else "{}"
            tempFile.delete()
            val obj = JSONObject(if (jsonResult.isBlank()) "{}" else jsonResult)
            
            val sectionsArr = obj.getJSONArray("sections")
            val sections = mutableListOf<ReflowSection>()
            for (i in 0 until sectionsArr.length()) {
                val secObj = sectionsArr.getJSONObject(i)
                val pArr = secObj.getJSONArray("paragraphs")
                val pars = mutableListOf<String>()
                for (j in 0 until pArr.length()) pars.add(pArr.getString(j))
                sections.add(ReflowSection(
                    pageNumber = secObj.getInt("pageNumber"),
                    title = if (secObj.isNull("title")) null else secObj.getString("title"),
                    paragraphs = pars
                ))
            }
            
            val bookmarksArr = obj.getJSONArray("bookmarks")
            val bookmarks = parseBookmarksArray(bookmarksArr)
            
            ReflowDocumentData(
                sections = sections,
                bookmarks = bookmarks,
                isScannedOnly = obj.getBoolean("isScannedOnly"),
                totalPages = obj.getInt("totalPages")
            )
        } catch (e: Exception) {
            AppLogger.e("PdfOutlineReader: Failed to extract reflow document", e)
            ReflowDocumentData(emptyList(), emptyList(), false, 0)
        }
    }

    private fun parseBookmarksArray(arr: JSONArray): List<OutlineBookmark> {
        val list = mutableListOf<OutlineBookmark>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(OutlineBookmark(
                title = obj.getString("title"),
                pageNumber = obj.getInt("pageNumber"),
                children = if (obj.has("children")) parseBookmarksArray(obj.getJSONArray("children")) else emptyList()
            ))
        }
        return list
    }

    suspend fun extractOutline(context: Context, sourceUri: Uri): List<OutlineBookmark> = loadReflowDocument(context, sourceUri).bookmarks
    suspend fun extractReflowContent(context: Context, sourceUri: Uri): List<ReflowSection> = loadReflowDocument(context, sourceUri).sections
}
