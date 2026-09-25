package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BookmarkItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val pageIndex: Int // 0-based
)

object PdfBookmarkEngine {

    suspend fun readBookmarks(context: Context, pdfUri: Uri): Result<List<BookmarkItem>> = withContext(Dispatchers.IO) {
        try {
            val jsonResult = PdfGateway.executeEngine(context, "BOOKMARK_READ", pdfUri, null, "{}")
            val arr = JSONArray(jsonResult)
            val result = mutableListOf<BookmarkItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(BookmarkItem(
                    title = obj.getString("title"),
                    pageIndex = obj.getInt("pageIndex")
                ))
            }
            Result.success(result)
        } catch (e: Exception) {
            AppLogger.e("PdfBookmarkEngine: Error reading bookmarks", e)
            Result.failure(e)
        }
    }

    suspend fun writeBookmarks(context: Context, sourcePdfUri: Uri, destPdfUri: Uri, bookmarks: List<BookmarkItem>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            for (item in bookmarks) {
                val obj = JSONObject()
                obj.put("title", item.title)
                obj.put("pageIndex", item.pageIndex)
                arr.put(obj)
            }
            PdfGateway.executeEngine(context, "BOOKMARK_WRITE", sourcePdfUri, destPdfUri, arr.toString())
            
            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(destPdfUri, FileUtils.getFileName(context, destPdfUri) ?: "bookmarked.pdf", "Updated PDF Bookmarks")
            
            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfBookmarkEngine: Error writing bookmarks", e)
            Result.failure(e)
        }
    }
}
