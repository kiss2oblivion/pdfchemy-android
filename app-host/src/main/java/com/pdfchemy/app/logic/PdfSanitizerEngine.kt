package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException

import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream

data class SanitizerAuditReport(
    val threatsFound: Int = 0,
    val jsCount: Int = 0,
    val launchActionsCount: Int = 0,
    val otherActionsCount: Int = 0,
    val untrustedUriCount: Int = 0,
    val attachmentCount: Int = 0,
    val uriCount: Int = 0,
    val hasMetadata: Boolean = false,
    val isClean: Boolean = true,
    val isEncrypted: Boolean = false,
    val parseFailed: Boolean = false,
    val failure: VanguardInspectionFailure? = null
)

sealed class VanguardThreatResult {
    object Clean : VanguardThreatResult()
    data class ExecutableThreat(val report: SanitizerAuditReport) : VanguardThreatResult()
    data class EncryptedCannotVerify(val uri: Uri) : VanguardThreatResult()
    data class ParseFailed(val uri: Uri, val failure: VanguardInspectionFailure = VanguardInspectionFailure.UNKNOWN) : VanguardThreatResult()
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

    internal fun classifyInspectionFailure(error: Exception): VanguardInspectionFailure = when (error) {
        is PdfGateway.WorkerAdmissionBusyException -> VanguardInspectionFailure.WORKER_BUSY
        is PdfGateway.WorkerTimeoutException -> VanguardInspectionFailure.WORKER_TIMEOUT
        is PdfGateway.WorkerDiedException, is PdfGateway.WorkerBindingException,
        is PdfGateway.WorkerFailure -> VanguardInspectionFailure.WORKER_UNAVAILABLE
        is com.pdfchemy.app.utils.DocumentStager.InputQuotaExceeded ->
            if (error.perFile) VanguardInspectionFailure.INPUT_SIZE_LIMIT else VanguardInspectionFailure.STAGING_CAPACITY
        is com.pdfchemy.app.utils.DocumentStager.StagingSlotsExceeded -> VanguardInspectionFailure.STAGING_CAPACITY
        is java.io.FileNotFoundException, is SecurityException -> VanguardInspectionFailure.ACCESS_FAILED
        else -> VanguardInspectionFailure.UNKNOWN
    }

    suspend fun auditDocumentThreats(
        context: Context,
        pdfUri: Uri
    ): SanitizerAuditReport {
        return try {
            val contract = PdfGateway.executeEngineTyped<SanitizeAuditContract>(
                context,
                "SANITIZE_AUDIT",
                pdfUri,
                null,
                "{}"
            )
            SanitizerAuditReport(
                threatsFound = contract.threatsFound,
                jsCount = contract.jsCount,
                launchActionsCount = contract.launchActionsCount,
                otherActionsCount = contract.otherActionsCount,
                untrustedUriCount = contract.untrustedUriCount,
                attachmentCount = contract.attachmentCount,
                uriCount = contract.uriCount,
                hasMetadata = contract.hasMetadata,
                isClean = contract.isClean,
                isEncrypted = contract.isEncrypted,
                parseFailed = contract.parseFailed,
                failure = contract.failure
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SanitizerAuditReport(threatsFound = 1, isClean = false, parseFailed = true,
                failure = classifyInspectionFailure(e))
        }
    }

    suspend fun auditDocumentThreats(
        context: Context,
        inputStream: InputStream
    ): SanitizerAuditReport {
        val snapshot = com.pdfchemy.app.utils.DocumentStager.stageStreamCancellable(context, inputStream)
        try {
            return auditDocumentThreats(context, snapshot.uri)
        } finally {
            com.pdfchemy.app.utils.DocumentStager.release(snapshot)
        }
    }

    suspend fun hasExecutableThreats(
        context: Context,
        pdfUri: Uri
    ): Boolean {
        val report = auditDocumentThreats(context, pdfUri)
        return !report.isClean || report.threatsFound != 0 || report.jsCount > 0 || report.launchActionsCount > 0 || report.otherActionsCount > 0 || report.untrustedUriCount > 0 || report.isEncrypted || report.parseFailed
    }

    suspend fun checkVanguardThreat(
        context: Context,
        pdfUri: Uri
    ): VanguardThreatResult {
        val report = auditDocumentThreats(context, pdfUri)
        if (report.isEncrypted) {
            return VanguardThreatResult.EncryptedCannotVerify(pdfUri)
        }
        if (report.jsCount > 0 || report.launchActionsCount > 0 || report.otherActionsCount > 0 || report.untrustedUriCount > 0) {
            return VanguardThreatResult.ExecutableThreat(report)
        }
        if (report.parseFailed || !report.isClean || report.threatsFound != 0) {
            return VanguardThreatResult.ParseFailed(pdfUri, report.failure ?: VanguardInspectionFailure.UNKNOWN)
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

        return try {
            val contract = PdfGateway.executeEngineTyped<SanitizerCleanContract>(
                context,
                "SANITIZE_CLEAN",
                sourceUri,
                destUri,
                params.toString()
            )
            SanitizerResult(
                isSuccess = contract.isSuccess,
                threatsRemoved = contract.threatsRemoved,
                jsRemoved = contract.jsRemoved,
                actionsRemoved = contract.actionsRemoved,
                metadataRemoved = contract.metadataRemoved,
                attachmentsRemoved = contract.attachmentsRemoved
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        val snapshot = com.pdfchemy.app.utils.DocumentStager.stageStreamCancellable(context, inputStream)
        var tempDest: File? = null
        try {
            val destination = File.createTempFile("sanitize_dest", ".pdf", context.cacheDir)
            tempDest = destination
            val res = sanitizeDocument(context, snapshot.uri, Uri.fromFile(destination), purgeJs, purgeActions, purgeMetadata, purgeAttachments)
            if (res.isSuccess) {
                destination.inputStream().use { inp ->
                    inp.copyTo(outStream)
                }
            }
            return res
        } finally {
            com.pdfchemy.app.utils.DocumentStager.release(snapshot)
            tempDest?.delete()
        }
    }
}
