package com.pdfchemy.app.logic

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import com.pdfchemy.app.utils.AppLogger

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

    fun getHistory(): List<HistoryItem> {
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
                        mimeType = obj.optString("mime", "").ifBlank { com.pdfchemy.app.utils.FileUtils.getMimeType(context, Uri.parse(obj.getString("uri")), obj.getString("name")) },
                        isDirectory = obj.optBoolean("directory", android.provider.DocumentsContract.isTreeUri(Uri.parse(obj.getString("uri"))))
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to parse history", e)
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun addHistoryItem(uri: Uri, name: String, action: String) {
        val isHistoryEnabled = settingsPrefs.getBoolean("history_enabled", false)
        if (!isHistoryEnabled) return

        val currentList = getHistory().toMutableList()
        val uriStr = uri.toString()

        
        // Remove existing item if it has the same URI
        currentList.removeAll { it.uriString == uriStr }
        
        currentList.add(
            HistoryItem(
                uriString = uriStr,
                name = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri)?.takeIf { it.isNotBlank() } ?: name,
                action = action,
                timestamp = System.currentTimeMillis(),
                mimeType = com.pdfchemy.app.utils.FileUtils.getMimeType(context, uri),
                isDirectory = android.provider.DocumentsContract.isTreeUri(uri)
            )
        )
        
        // Keep only the most recent 20 items
        val limitedList = currentList.sortedByDescending { it.timestamp }.take(20)
        
        val jsonArray = JSONArray()
        for (item in limitedList) {
            val obj = JSONObject()
            obj.put("uri", item.uriString)
            obj.put("name", item.name)
            obj.put("action", item.action)
            obj.put("timestamp", item.timestamp)
            obj.put("mime", item.mimeType)
            obj.put("directory", item.isDirectory)
            jsonArray.put(obj)
        }
        
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }
    
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
    fun remove(uriString: String) {
        val items = getHistory().filterNot { it.uriString == uriString }
        val json = JSONArray()
        items.forEach { json.put(JSONObject().put("uri", it.uriString).put("name", it.name)
            .put("action", it.action).put("timestamp", it.timestamp).put("mime", it.mimeType).put("directory", it.isDirectory)) }
        prefs.edit().putString(KEY_HISTORY, json.toString()).apply()
    }
}
