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
        val tempFile = java.io.File.createTempFile("outline_", ".json", context.cacheDir)
        try {
            val isEpubDoc = isEpub(context, sourceUri)
            val params = JSONObject().apply {
                put("isEpub", isEpubDoc)
            }.toString()
            
            val destUri = Uri.fromFile(tempFile)
            PdfGateway.executeEngine(context, "OUTLINE_READ", sourceUri, destUri, params)
            
            val contract = com.pdfchemy.app.jail.WorkerResponseValidator.parseOutlineFile(tempFile)
            
            fun mapBookmark(b: OutlineBookmarkContract): OutlineBookmark {
                return OutlineBookmark(
                    title = b.title,
                    pageNumber = b.pageNumber,
                    children = b.children.map(::mapBookmark)
                )
            }
            
            ReflowDocumentData(
                sections = contract.sections.map { s ->
                    ReflowSection(
                        pageNumber = s.pageNumber,
                        title = s.title,
                        paragraphs = s.paragraphs
                    )
                },
                bookmarks = contract.bookmarks.map(::mapBookmark),
                isScannedOnly = contract.isScannedOnly,
                totalPages = contract.totalPages
            )
        } catch (e: Exception) {
            AppLogger.e("PdfOutlineReader: Failed to extract reflow document", e)
            ReflowDocumentData(emptyList(), emptyList(), false, 0)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun extractOutline(context: Context, sourceUri: Uri): List<OutlineBookmark> = loadReflowDocument(context, sourceUri).bookmarks
    suspend fun extractReflowContent(context: Context, sourceUri: Uri): List<ReflowSection> = loadReflowDocument(context, sourceUri).sections
}
