package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PageAction(
    val originalPageIndex: Int? = null,
    val rotationDegrees: Int = 0,
    val isBlank: Boolean = false
)

object PdfPageOrganizer {

    suspend fun reorganizePages(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        actions: List<PageAction>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray()
            for (action in actions) {
                val obj = JSONObject()
                if (action.originalPageIndex != null) {
                    obj.put("originalPageIndex", action.originalPageIndex)
                }
                obj.put("rotationDegrees", action.rotationDegrees)
                obj.put("isBlank", action.isBlank)
                arr.put(obj)
            }
            
            val params = JSONObject().apply {
                put("actions", arr)
            }.toString()

            PdfGateway.executeEngine(context, "PAGE_ORGANIZE", sourceUri, destUri, params)
            true
        } catch (e: Exception) {
            AppLogger.e("Failed to reorganize PDF pages: ", e)
            false
        }
    }
}
