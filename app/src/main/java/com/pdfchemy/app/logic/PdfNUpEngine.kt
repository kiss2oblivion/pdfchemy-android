package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri

import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import org.json.JSONObject

enum class NUpLayout(val cols: Int, val rows: Int, val pagesPerSheet: Int, val isLandscapeDefault: Boolean) {
    TWO_UP(cols = 1, rows = 2, pagesPerSheet = 2, isLandscapeDefault = false),
    FOUR_UP(cols = 2, rows = 2, pagesPerSheet = 4, isLandscapeDefault = true),
    SIX_UP(cols = 2, rows = 3, pagesPerSheet = 6, isLandscapeDefault = false),
    NINE_UP(cols = 3, rows = 3, pagesPerSheet = 9, isLandscapeDefault = true),
    SIXTEEN_UP(cols = 4, rows = 4, pagesPerSheet = 16, isLandscapeDefault = true)
}

enum class NUpOrder {
    HORIZONTAL, // Across then down
    VERTICAL    // Down then across
}

data class NUpConfig(
    val layout: NUpLayout = NUpLayout.FOUR_UP,
    val order: NUpOrder = NUpOrder.HORIZONTAL,
    val paperSize: TargetPaperSize = TargetPaperSize.A4,
    val drawBorders: Boolean = true,
    val marginPt: Float = 24f,
    val spacingPt: Float = 12f
)

object PdfNUpEngine {

    suspend fun generateNUpPdf(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        config: NUpConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Boolean> {
        return try {
            val params = JSONObject().apply {
                put("layoutCols", config.layout.cols)
                put("layoutRows", config.layout.rows)
                put("pagesPerSheet", config.layout.pagesPerSheet)
                put("isLandscapeDefault", config.layout.isLandscapeDefault)
                put("orderHorizontal", config.order == NUpOrder.HORIZONTAL)
                put("paperWidthPts", config.paperSize.widthPts)
                put("paperHeightPts", config.paperSize.heightPts)
                put("drawBorders", config.drawBorders)
                put("marginPt", config.marginPt.toDouble())
                put("spacingPt", config.spacingPt.toDouble())
            }

            val resultStr = PdfGateway.executeEngine(
                context,
                "NUP_GENERATE",
                sourcePdfUri,
                destPdfUri,
                params.toString()
            )

            val json = JSONObject(resultStr)
            if (json.optBoolean("success", false)) {
                val historyRepo = HistoryRepository(context)
                historyRepo.addHistoryItem(
                    destPdfUri,
                    FileUtils.getFileName(context, destPdfUri) ?: "nup_handout.pdf",
                    "${config.layout.pagesPerSheet}-Up Grid Handout"
                )
                Result.success(true)
            } else {
                Result.failure(Exception(json.optString("error", "Failed to generate N-Up")))
            }
        } catch (e: Exception) {
            AppLogger.e("PdfNUpEngine: Error generating N-Up grid", e)
            Result.failure(e)
        }
    }
}
