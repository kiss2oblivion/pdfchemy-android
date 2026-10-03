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

            val contract = PdfGateway.executeEngineTyped<RepairDiagnoseContract>(
                context,
                "REPAIR_DIAGNOSE",
                pdfUri,
                null,
                "{}"
            )

            return Result.success(
                RepairDiagnostic(
                    hasValidHeader = contract.hasValidHeader,
                    hasValidEof = contract.hasValidEof,
                    recoveredPages = contract.recoveredPages,
                    isEncrypted = contract.isEncrypted,
                    issueSummary = contract.issueSummary
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

            val contract = PdfGateway.executeEngineTyped<RepairApplyContract>(
                context,
                "REPAIR_APPLY",
                sourcePdfUri,
                destPdfUri,
                "{}"
            )

            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(
                destPdfUri,
                FileUtils.getFileName(context, destPdfUri) ?: "repaired.pdf",
                "Repaired Corrupted PDF"
            )

            return Result.success(contract.recoveredPages)
        } catch (e: Exception) {
            AppLogger.e("PdfRepairEngine: Error repairing PDF", e)
            return Result.failure(e)
        }
    }
}
