package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CheckSeverity { INFO, WARNING, ERROR }

data class ComplianceCheckItem(
    val rule: String,
    val isPassed: Boolean,
    val details: String,
    val severity: CheckSeverity
)

data class PdfAReport(
    val pdfaVersionDetected: String,
    val complianceScore: Int,
    val checks: List<ComplianceCheckItem>,
    val isCompliant: Boolean
)

object PdfArchiveValidatorEngine {

    suspend fun inspectPdfACompliance(context: Context, pdfUri: Uri): Result<PdfAReport> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<ArchiveInspectContract>(context, "ARCHIVE_INSPECT", pdfUri, null, "{}")
            val checks = contract.checks.map {
                ComplianceCheckItem(
                    rule = it.rule,
                    isPassed = it.isPassed,
                    details = it.details,
                    severity = try { CheckSeverity.valueOf(it.severity) } catch (_: Exception) { CheckSeverity.INFO }
                )
            }
            val report = PdfAReport(
                pdfaVersionDetected = contract.pdfaVersionDetected,
                complianceScore = contract.complianceScore,
                checks = checks,
                isCompliant = contract.isCompliant
            )
            Result.success(report)
        } catch (e: Exception) {
            AppLogger.e("PdfArchiveValidatorEngine: Error validating PDF/A", e)
            Result.failure(e)
        }
    }

    suspend fun convertToPdfA(context: Context, sourceUri: Uri, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            PdfGateway.executeEngine(context, "ARCHIVE_CONVERT", sourceUri, destUri, "{}")
            true
        } catch (e: Exception) {
            AppLogger.e("PdfArchiveValidatorEngine: Error converting to PDF/A", e)
            false
        }
    }
}
