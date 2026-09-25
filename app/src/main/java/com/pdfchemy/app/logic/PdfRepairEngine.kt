package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri

import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import org.json.JSONObject

data class RepairDiagnostic(
    val hasValidHeader: Boolean = false,
    val hasValidEof: Boolean = false,
    val recoveredPages: Int = 0,
    val isEncrypted: Boolean = false,
    val issueSummary: String = ""
)

object PdfRepairEngine {

    suspend fun diagnosePdf(
        context: Context,
        pdfUri: Uri
    ): Result<RepairDiagnostic> {
        try {
            val fileSize = FileUtils.getFileSize(context, pdfUri)
            if (fileSize > 100 * 1024 * 1024) {
                return Result.failure(IllegalStateException("File is too large to repair (exceeds 100MB limit)"))
            }

            val resultStr = PdfGateway.executeEngine(
                context,
                "REPAIR_DIAGNOSE",
                pdfUri,
                null,
                "{}"
            )

            val json = JSONObject(resultStr)
            if (json.has("error")) {
                return Result.failure(Exception(json.getString("error")))
            }

            return Result.success(
                RepairDiagnostic(
                    hasValidHeader = json.optBoolean("hasValidHeader", false),
                    hasValidEof = json.optBoolean("hasValidEof", false),
                    recoveredPages = json.optInt("recoveredPages", 0),
                    isEncrypted = json.optBoolean("isEncrypted", false),
                    issueSummary = json.optString("issueSummary", "")
                )
            )
        } catch (e: Exception) {
            AppLogger.e("PdfRepairEngine: Error diagnosing PDF", e)
            return Result.failure(e)
        }
    }

    suspend fun repairPdf(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri
    ): Result<Int> {
        try {
            val fileSize = FileUtils.getFileSize(context, sourcePdfUri)
            if (fileSize > 100 * 1024 * 1024) {
                return Result.failure(IllegalStateException("File is too large to repair (exceeds 100MB limit)"))
            }

            val resultStr = PdfGateway.executeEngine(
                context,
                "REPAIR_APPLY",
                sourcePdfUri,
                destPdfUri,
                "{}"
            )

            val json = JSONObject(resultStr)
            if (!json.optBoolean("success", false)) {
                return Result.failure(Exception(json.optString("error", "Unknown repair error")))
            }

            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(
                destPdfUri,
                FileUtils.getFileName(context, destPdfUri) ?: "repaired.pdf",
                "Repaired Corrupted PDF"
            )

            return Result.success(json.optInt("recoveredPages", 0))
        } catch (e: Exception) {
            AppLogger.e("PdfRepairEngine: Error repairing PDF", e)
            return Result.failure(e)
        }
    }
}
