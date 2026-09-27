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
    val timestamp: Long
)

class HistoryRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pdfchemy_history", Context.MODE_PRIVATE)
    private val KEY_HISTORY = "recent_files"

    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)

    fun getHistory(): List<HistoryItem> {
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
                        timestamp = obj.getLong("timestamp")
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to parse history", e)
        }
        return list.sortedByDescending { it.timestamp }
    }

    fun addHistoryItem(uri: Uri, name: String, action: String) {
        val isHistoryEnabled = settingsPrefs.getBoolean("history_enabled", true)
        if (!isHistoryEnabled) return

        val currentList = getHistory().toMutableList()
        val uriStr = uri.toString()

        
        // Remove existing item if it has the same URI
        currentList.removeAll { it.uriString == uriStr }
        
        currentList.add(
            HistoryItem(
                uriString = uriStr,
                name = name,
                action = action,
                timestamp = System.currentTimeMillis()
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
            jsonArray.put(obj)
        }
        
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }
    
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
}
