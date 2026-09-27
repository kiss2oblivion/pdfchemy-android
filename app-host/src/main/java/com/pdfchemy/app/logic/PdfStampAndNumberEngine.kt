package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

import org.json.JSONObject

data class WatermarkOptions(
    val text: String? = null,
    val imageBitmap: Bitmap? = null,
    val rotationDegrees: Float = 45f,
    val opacity: Float = 0.3f,
    val fontSize: Float = 40f,
    val isTiled: Boolean = false
)

enum class NumberPosition {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT
}

enum class NumberFormat {
    SIMPLE,        // "1"
    PAGE_X_OF_Y,   // "Page 1 of 5"
    SLASH          // "1 / 5"
}

data class PageNumberOptions(
    val position: NumberPosition = NumberPosition.BOTTOM_CENTER,
    val format: NumberFormat = NumberFormat.PAGE_X_OF_Y,
    val skipFirstPage: Boolean = false,
    val fontSize: Float = 10f,
    val marginPts: Float = 36f
)

data class BatesOptions(
    val prefix: String = "",
    val suffix: String = "",
    val startNumber: Int = 1,
    val digits: Int = 6,
    val position: NumberPosition = NumberPosition.BOTTOM_RIGHT,
    val fontSize: Float = 10f,
    val marginPts: Float = 36f
)

object PdfStampAndNumberEngine {

    suspend fun addWatermark(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        options: WatermarkOptions
    ): Boolean {
        val params = JSONObject().apply {
            put("text", options.text)
            put("rotationDegrees", options.rotationDegrees.toDouble())
            put("opacity", options.opacity.toDouble())
            put("fontSize", options.fontSize.toDouble())
            put("isTiled", options.isTiled)
        }

        val resultStr = PdfGateway.executeEngine(
            context,
            "WATERMARK",
            sourceUri,
            destUri,
            params.toString()
        )
        return try {
            JSONObject(resultStr).optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun addPageNumbers(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        options: PageNumberOptions
    ): Boolean {
        val params = JSONObject().apply {
            put("position", options.position.name)
            put("format", options.format.name)
            put("skipFirstPage", options.skipFirstPage)
            put("fontSize", options.fontSize.toDouble())
            put("marginPts", options.marginPts.toDouble())
        }

        val resultStr = PdfGateway.executeEngine(
            context,
            "PAGE_NUMBERS",
            sourceUri,
            destUri,
            params.toString()
        )
        return try {
            JSONObject(resultStr).optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun applyBatesStamping(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        options: BatesOptions
    ): Boolean {
        val params = JSONObject().apply {
            put("prefix", options.prefix)
            put("suffix", options.suffix)
            put("startNumber", options.startNumber)
            put("digits", options.digits)
            put("position", options.position.name)
            put("fontSize", options.fontSize.toDouble())
            put("marginPts", options.marginPts.toDouble())
        }

        val resultStr = PdfGateway.executeEngine(
            context,
            "BATES_STAMP",
            sourceUri,
            destUri,
            params.toString()
        )
        return try {
            JSONObject(resultStr).optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }
}
