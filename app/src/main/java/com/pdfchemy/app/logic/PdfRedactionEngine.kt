package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class RedactPattern(val label: String, val regex: String) {
    CREDIT_CARD("Credit Card Numbers", "\\b(?:\\d[ -]*?){13,16}\\b"),
    SSN_US("Social Security Number (US)", "\\b\\d{3}[- ]?\\d{2}[- ]?\\d{4}\\b"),
    EMAIL("Email Addresses", "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"),
    PHONE_NUMBERS("Phone Numbers", "\\b(?:\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b"),
    IBAN("IBAN / Bank Accounts", "\\b[A-Z]{2}[0-9]{2}(?:[ ]?[0-9A-Z]{4}){3,7}\\b")
}

data class RedactionBox(
    val pageIndex: Int,
    val xRatio: Float,
    val yRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
    val normalizedRect: RectF = RectF(xRatio, yRatio, xRatio + widthRatio, yRatio + heightRatio),
    val overlayLabel: String = "REDACTED"
) {
    constructor(pageIndex: Int, normalizedRect: RectF, overlayLabel: String? = null) : this(
        pageIndex = pageIndex,
        xRatio = normalizedRect.left,
        yRatio = normalizedRect.top,
        widthRatio = normalizedRect.width(),
        heightRatio = normalizedRect.height(),
        normalizedRect = normalizedRect,
        overlayLabel = overlayLabel ?: "REDACTED"
    )
}

data class RedactionConfig(
    val isBlackout: Boolean = true,
    val defaultOverlayText: String = "",
    val forensicSanitize: Boolean = true
)

object PdfRedactionEngine {
    
    suspend fun searchRedactionTargets(
        context: Context,
        pdfUri: Uri,
        query: String,
        isRegex: Boolean = false
    ): Result<List<RedactionBox>> = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("query", query)
            params.put("isRegex", isRegex)

            val resultStr = PdfGateway.executeEngine(context, "SEARCH_REDACT", pdfUri, null, params.toString())
            val json = JSONObject(resultStr)

            if (json.has("error")) return@withContext Result.failure(Exception(json.getString("error")))

            val boxesArr = json.optJSONArray("boxes")
            val boxes = mutableListOf<RedactionBox>()
            if (boxesArr != null) {
                for (i in 0 until boxesArr.length()) {
                    val b = boxesArr.optJSONObject(i) ?: continue
                    boxes.add(
                        RedactionBox(
                            pageIndex = b.optInt("pageIndex", 0),
                            xRatio = b.optDouble("left", 0.0).toFloat(),
                            yRatio = b.optDouble("top", 0.0).toFloat(),
                            widthRatio = b.optDouble("right", 0.0).toFloat() - b.optDouble("left", 0.0).toFloat(),
                            heightRatio = b.optDouble("bottom", 0.0).toFloat() - b.optDouble("top", 0.0).toFloat(),
                            overlayLabel = b.optString("overlayLabel", "REDACTED")
                        )
                    )
                }
            }
            Result.success(boxes)
        } catch (e: Exception) {
            AppLogger.e("PdfRedactionEngine: Error searching targets", e)
            Result.failure(e)
        }
    }

    suspend fun smartRedact(
        context: Context,
        pdfUri: Uri,
        destUri: Uri,
        patterns: List<RedactPattern>,
        config: RedactionConfig = RedactionConfig(isBlackout = true, defaultOverlayText = "REDACTED", forensicSanitize = true)
    ): Result<Int> {
        val combinedRegex = patterns.joinToString(separator = "|") { it.regex }
        val searchResult = searchRedactionTargets(context, pdfUri, combinedRegex, isRegex = true)
        if (searchResult.isFailure) return Result.failure(searchResult.exceptionOrNull() ?: Exception("Unknown error"))
        
        val boxes = searchResult.getOrNull() ?: emptyList()
        if (boxes.isEmpty()) return Result.success(0)
        
        return applyRedactions(context, pdfUri, destUri, boxes, config)
    }

    suspend fun applyRedactions(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        redactions: List<RedactionBox>,
        config: RedactionConfig = RedactionConfig()
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            val configObj = JSONObject()
            configObj.put("isBlackout", config.isBlackout)
            configObj.put("defaultOverlayText", config.defaultOverlayText)
            configObj.put("forensicSanitize", config.forensicSanitize)
            params.put("config", configObj)

            val boxesArr = JSONArray()
            for (box in redactions) {
                val boxObj = JSONObject()
                boxObj.put("pageIndex", box.pageIndex)
                boxObj.put("xRatio", box.xRatio.toDouble())
                boxObj.put("yRatio", box.yRatio.toDouble())
                boxObj.put("widthRatio", box.widthRatio.toDouble())
                boxObj.put("heightRatio", box.heightRatio.toDouble())
                boxesArr.put(boxObj)
            }
            params.put("boxes", boxesArr)

            val resultStr = com.pdfchemy.app.logic.PdfGateway.executeEngine(context, "REDACT", sourceUri, destUri, params.toString())
            val json = JSONObject(resultStr)
            
            if (json.has("error")) return@withContext Result.failure(Exception(json.getString("error")))
            
            val success = json.optBoolean("success", false)
            if (success) {
                Result.success(json.optInt("count", redactions.size))
            } else {
                Result.failure(Exception("Unknown redaction failure"))
            }
        } catch (e: Exception) {
            AppLogger.e("PdfRedactionEngine: Error applying redactions via gateway", e)
            Result.failure(e)
        }
    }
}
