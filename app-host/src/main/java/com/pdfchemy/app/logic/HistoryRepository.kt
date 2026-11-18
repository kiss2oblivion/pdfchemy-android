package com.pdfchemy.app.logic

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.pdfchemy.app.utils.AppLogger
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val uriString: String,
    val name: String,
    val action: String,
    val timestamp: Long,
    val mimeType: String = "",
    val isDirectory: Boolean = false
)

class HistoryRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pdfchemy_history", Context.MODE_PRIVATE)
    private val KEY_HISTORY = "recent_files"

    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)

    @Synchronized fun getHistory(): List<HistoryItem> {
        if (!settingsPrefs.getBoolean("history_enabled", false)) {
            clearHistory()
            return emptyList()
        }
        val jsonString = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        val list = mutableListOf<HistoryItem>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        uriString = obj.getString("uri"),
                        name = obj.getString("name"),
                        action = obj.getString("action"),
                        timestamp = obj.getLong("timestamp"),
                        mimeType = obj.optString("mime", "").ifBlank { com.pdfchemy.app.utils.FileUtils.getMimeType(context, obj.getString("uri").toUri(), obj.getString("name"), allowProviderQuery = false) },
                        isDirectory = obj.optBoolean("directory", android.provider.DocumentsContract.isTreeUri(obj.getString("uri").toUri()))
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to parse history", e)
        }
        return list.sortedByDescending { it.timestamp }
    }

    /** Provider metadata is resolved before acquiring the persistence monitor. Call off Main for enrichment. */
    fun addHistoryItem(uri: Uri, name: String, action: String, canRecord: () -> Boolean = { true }) {
        if (!settingsPrefs.getBoolean("history_enabled", false)) return
        val provider = com.pdfchemy.app.utils.ProviderMetadata.read(context, uri)
        val fallbackName = if (uri.scheme == "file") com.pdfchemy.app.utils.FileUtils.getFileName(context, uri)
            else uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.substringAfterLast('\\')
        val actualName = provider.name?.takeIf { it.isNotBlank() }
            ?: fallbackName?.takeIf { it.isNotBlank() && (uri.scheme == "file" || '.' in it) } ?: name
        val item = HistoryItem(uri.toString(), actualName, action, System.currentTimeMillis(),
            com.pdfchemy.app.utils.FileUtils.getMimeType(context, uri, actualName, allowProviderQuery = false),
            android.provider.DocumentsContract.isTreeUri(uri))
        synchronized(this) {
            // Removal, clearing, disabling history or ending the owner must defeat a late metadata response.
            if (!settingsPrefs.getBoolean("history_enabled", false) || !canRecord()) return
            val current = getHistory().filterNot { it.uriString == item.uriString }
            persistList((listOf(item) + current).sortedByDescending { it.timestamp }.take(20))
        }
    }

    @Synchronized fun removeHistoryItem(uriString: String) {
        if (uriString.isBlank()) return
        val remaining = getHistory().filterNot { it.uriString == uriString }
        persistList(remaining)
    }

    @Synchronized fun clearHistory() {
        prefs.edit { remove(KEY_HISTORY) }
    }

    private fun persistList(items: List<HistoryItem>) {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("uri", item.uriString)
            obj.put("name", item.name)
            obj.put("action", item.action)
            obj.put("timestamp", item.timestamp)
            obj.put("mime", item.mimeType)
            obj.put("directory", item.isDirectory)
            jsonArray.put(obj)
        }
        prefs.edit { putString(KEY_HISTORY, jsonArray.toString()) }
    }
    fun remove(uriString: String) = removeHistoryItem(uriString)
}
