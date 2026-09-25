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
            val jsonResult = PdfGateway.executeEngine(context, "ARCHIVE_INSPECT", pdfUri, null, "{}")
            val obj = JSONObject(jsonResult)
            
            val checksArray = obj.getJSONArray("checks")
            val checks = mutableListOf<ComplianceCheckItem>()
            for (i in 0 until checksArray.length()) {
                val checkObj = checksArray.getJSONObject(i)
                checks.add(ComplianceCheckItem(
                    rule = checkObj.getString("rule"),
                    isPassed = checkObj.getBoolean("isPassed"),
                    details = checkObj.getString("details"),
                    severity = CheckSeverity.valueOf(checkObj.getString("severity"))
                ))
            }
            
            val report = PdfAReport(
                pdfaVersionDetected = obj.getString("pdfaVersionDetected"),
                complianceScore = obj.getInt("complianceScore"),
                checks = checks,
                isCompliant = obj.getBoolean("isCompliant")
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
