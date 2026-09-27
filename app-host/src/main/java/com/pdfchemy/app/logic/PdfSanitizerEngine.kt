package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri

import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream

data class SanitizerAuditReport(
    val threatsFound: Int = 0,
    val jsCount: Int = 0,
    val launchActionsCount: Int = 0,
    val attachmentCount: Int = 0,
    val uriCount: Int = 0,
    val hasMetadata: Boolean = false,
    val isClean: Boolean = true,
    val isEncrypted: Boolean = false,
    val parseFailed: Boolean = false
)

sealed class VanguardThreatResult {
    object Clean : VanguardThreatResult()
    data class ExecutableThreat(val report: SanitizerAuditReport) : VanguardThreatResult()
    data class EncryptedCannotVerify(val uri: Uri) : VanguardThreatResult()
    data class ParseFailed(val uri: Uri) : VanguardThreatResult()
}

data class SanitizerResult(
    val isSuccess: Boolean,
    val threatsRemoved: Int,
    val jsRemoved: Int,
    val actionsRemoved: Int,
    val metadataRemoved: Boolean,
    val attachmentsRemoved: Int
)

object PdfSanitizerEngine {

    suspend fun auditDocumentThreats(
        context: Context,
        pdfUri: Uri
    ): SanitizerAuditReport {
        val resultStr = PdfGateway.executeEngine(
            context,
            "SANITIZE_AUDIT",
            pdfUri,
            null,
            "{}"
        )
        return try {
            val json = JSONObject(resultStr)
            SanitizerAuditReport(
                threatsFound = json.optInt("threatsFound", 0),
                jsCount = json.optInt("jsCount", 0),
                launchActionsCount = json.optInt("launchActionsCount", 0),
                attachmentCount = json.optInt("attachmentCount", 0),
                uriCount = json.optInt("uriCount", 0),
                hasMetadata = json.optBoolean("hasMetadata", false),
                isClean = json.optBoolean("isClean", true),
                isEncrypted = json.optBoolean("isEncrypted", false),
                parseFailed = json.optBoolean("parseFailed", false)
            )
        } catch (e: Exception) {
            SanitizerAuditReport(threatsFound = 1, isClean = false, parseFailed = true)
        }
    }

    suspend fun auditDocumentThreats(
        context: Context,
        inputStream: InputStream
    ): SanitizerAuditReport {
        val tempFile = File.createTempFile("audit_tmp", ".pdf", context.cacheDir)
        try {
            FileOutputStream(tempFile).use { out ->
                inputStream.copyTo(out)
            }
            return auditDocumentThreats(context, Uri.fromFile(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    suspend fun hasExecutableThreats(
        context: Context,
        pdfUri: Uri
    ): Boolean {
        val report = auditDocumentThreats(context, pdfUri)
        return report.jsCount > 0 || report.launchActionsCount > 0 || report.attachmentCount > 0 || report.uriCount > 0 || report.isEncrypted || report.parseFailed
    }

    suspend fun checkVanguardThreat(
        context: Context,
        pdfUri: Uri
    ): VanguardThreatResult {
        val report = auditDocumentThreats(context, pdfUri)
        if (report.isEncrypted) {
            return VanguardThreatResult.EncryptedCannotVerify(pdfUri)
        }
        if (report.jsCount > 0 || report.launchActionsCount > 0 || report.attachmentCount > 0 || report.uriCount > 0) {
            return VanguardThreatResult.ExecutableThreat(report)
        }
        if (report.parseFailed) {
            return VanguardThreatResult.ParseFailed(pdfUri)
        }
        return VanguardThreatResult.Clean
    }

    suspend fun sanitizeDocument(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        purgeJs: Boolean = true,
        purgeActions: Boolean = true,
        purgeMetadata: Boolean = true,
        purgeAttachments: Boolean = true
    ): SanitizerResult {
        val params = JSONObject().apply {
            put("purgeJs", purgeJs)
            put("purgeActions", purgeActions)
            put("purgeMetadata", purgeMetadata)
            put("purgeAttachments", purgeAttachments)
        }

        val resultStr = PdfGateway.executeEngine(
            context,
            "SANITIZE_CLEAN",
            sourceUri,
            destUri,
            params.toString()
        )
        return try {
            val json = JSONObject(resultStr)
            SanitizerResult(
                isSuccess = json.optBoolean("isSuccess", false),
                threatsRemoved = json.optInt("threatsRemoved", 0),
                jsRemoved = json.optInt("jsRemoved", 0),
                actionsRemoved = json.optInt("actionsRemoved", 0),
                metadataRemoved = json.optBoolean("metadataRemoved", false),
                attachmentsRemoved = json.optInt("attachmentsRemoved", 0)
            )
        } catch (e: Exception) {
            SanitizerResult(false, 0, 0, 0, false, 0)
        }
    }

    suspend fun sanitizeDocument(
        context: Context,
        inputStream: InputStream,
        outStream: java.io.OutputStream,
        purgeJs: Boolean = true,
        purgeActions: Boolean = true,
        purgeMetadata: Boolean = true,
        purgeAttachments: Boolean = true
    ): SanitizerResult {
        val tempSource = File.createTempFile("sanitize_src", ".pdf", context.cacheDir)
        val tempDest = File.createTempFile("sanitize_dest", ".pdf", context.cacheDir)
        try {
            FileOutputStream(tempSource).use { out ->
                inputStream.copyTo(out)
            }
            val res = sanitizeDocument(context, Uri.fromFile(tempSource), Uri.fromFile(tempDest), purgeJs, purgeActions, purgeMetadata, purgeAttachments)
            if (res.isSuccess) {
                tempDest.inputStream().use { inp ->
                    inp.copyTo(outStream)
                }
            }
            return res
        } finally {
            tempSource.delete()
            tempDest.delete()
        }
    }
}
