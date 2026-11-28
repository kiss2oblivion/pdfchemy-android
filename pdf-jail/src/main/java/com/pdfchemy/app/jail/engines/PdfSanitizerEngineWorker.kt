package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.json.JSONObject
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import java.io.File
import com.pdfchemy.app.jail.boundedFileOutput

object PdfSanitizerEngineWorker {
    fun audit(sourceFd: ParcelFileDescriptor): String {
        var stage = com.pdfchemy.app.logic.VanguardInspectionFailure.PDF_READ_FAILED
        return try {
            FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, "", com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
                stage = com.pdfchemy.app.logic.VanguardInspectionFailure.ACTIVE_CONTENT_INSPECTION_FAILED
                val f = ActiveContentScrubber.inspect(document)
                stage = com.pdfchemy.app.logic.VanguardInspectionFailure.METADATA_INSPECTION_FAILED
                val metadata = PdfPrivacyCleanup.hasMetadata(document)
                val executableThreats = f.javascript + f.launches + f.actions + f.untrustedUris
                val encrypted = document.isEncrypted
                // The legacy count includes a blocking sentinel for encrypted/incomplete audits.
                JSONObject().put("threatsFound", if (encrypted) maxOf(1, executableThreats) else executableThreats)
                    .put("isClean", executableThreats == 0 && !encrypted)
                    .put("jsCount", f.javascript).put("launchActionsCount", f.launches).put("otherActionsCount", f.actions)
                    .put("untrustedUriCount", f.untrustedUris).put("attachmentCount", f.attachments).put("uriCount", f.uris)
                    .put("hasMetadata", metadata).put("isEncrypted", encrypted).put("parseFailed", false).toString()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val encrypted = e is InvalidPasswordException
            val failure = if (e is InspectionGraphLimitException) com.pdfchemy.app.logic.VanguardInspectionFailure.GRAPH_LIMIT else stage
            JSONObject().put("threatsFound", 1).put("isClean", false)
                .put("jsCount", 0).put("launchActionsCount", 0).put("otherActionsCount", 0)
                .put("untrustedUriCount", 0).put("attachmentCount", 0).put("uriCount", 0)
                .put("hasMetadata", false).put("isEncrypted", encrypted).put("parseFailed", !encrypted)
                .apply { if (!encrypted) put("inspectionFailure", failure.name) }.toString()
        }
    }
    fun sanitize(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val p = JSONObject(paramsJson)
        val purgeJs = p.optBoolean("purgeJs", true)
        val purgeActions = p.optBoolean("purgeActions", true)
        val purgeAttachments = p.optBoolean("purgeAttachments", true)
        val purgeMetadata = p.optBoolean("purgeMetadata", true)
        val requireVanguardClean = p.optBoolean("requireVanguardClean", false)
        if (requireVanguardClean) require(purgeJs && purgeActions) { "Verified recovery requires active-content removal" }
        val scratch = com.pdfchemy.app.jail.JailScratch.createTempFile("sanitized_", ".pdf")
        try {
            var originalPageCount = 0
            val removed = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
                originalPageCount = document.numberOfPages
                if (requireVanguardClean) check(!document.isEncrypted && originalPageCount > 0) { "Cannot verify encrypted or empty PDF" }
                val findings = cleanDocument(document, purgeJs, purgeActions, purgeMetadata, purgeAttachments)
                boundedFileOutput(scratch).use { document.save(it) }
                findings.selected(purgeJs, purgeActions, purgeAttachments)
            }
            com.pdfchemy.app.jail.CapabilityIo.input(scratch).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
                validateCleanDocument(document, purgeJs, purgeActions, purgeMetadata, purgeAttachments,
                    expectedPages = if (requireVanguardClean) originalPageCount else null)
            }
            boundedFileOutput(targetFd.fileDescriptor).use { output -> com.pdfchemy.app.jail.CapabilityIo.input(scratch).use { it.copyTo(output) } }
            return JSONObject().put("isSuccess", true).put("threatsRemoved", removed.total).put("jsRemoved", removed.javascript)
                .put("actionsRemoved", removed.launches + removed.actions + removed.untrustedUris + removed.uris).put("attachmentsRemoved", removed.attachments).put("metadataRemoved", purgeMetadata).toString()
        } finally { scratch.delete() }
    }
    private fun verifyRecoveredPageCount(document: PDDocument, expectedPages: Int) {
        check(!document.isEncrypted && expectedPages > 0 && document.numberOfPages == expectedPages) {
            "Recovered PDF encryption or page count not verified"
        }
    }

    private fun verifyActiveContent(findings: ActiveContentScrubber.Findings) {
        check(findings.javascript == 0 && findings.launches == 0 && findings.actions == 0 &&
            findings.untrustedUris == 0) { "Recovered PDF contains prohibited active content" }
    }

    internal fun verifyRecoveredDocument(document: PDDocument, expectedPages: Int) {
        verifyRecoveredPageCount(document, expectedPages)
        verifyActiveContent(ActiveContentScrubber.inspect(document))
        PdfPrivacyCleanup.validatePrivacy(document, inspectMetadata = true,
            requireNoMetadata = false, requireNoAttachments = false)
    }

    /** Shared by the FD worker and saved-document regressions; counts precede removal. */
    internal fun cleanDocument(document: PDDocument, purgeJs: Boolean, purgeActions: Boolean,
                               purgeMetadata: Boolean, purgeAttachments: Boolean): ActiveContentScrubber.Findings {
        val findings = ActiveContentScrubber.inspect(document)
        // Capture all selected private identities together before any carrier links are severed.
        PdfPrivacyCleanup.removeSelected(document, purgeMetadata, purgeAttachments)
        ActiveContentScrubber.inspect(document, true, purgeJs, purgeActions, purgeAttachments)
        return findings
    }

    internal fun validateCleanDocument(document: PDDocument, purgeJs: Boolean, purgeActions: Boolean,
                                       purgeMetadata: Boolean, purgeAttachments: Boolean,
                                       expectedPages: Int? = null) {
        if (expectedPages != null) verifyRecoveredPageCount(document, expectedPages)
        val findings = ActiveContentScrubber.inspect(document)
        require(findings.selected(purgeJs, purgeActions, purgeAttachments).total == 0) { "Prohibited carriers survived sanitization" }
        if (expectedPages != null) verifyActiveContent(findings)
        PdfPrivacyCleanup.validatePrivacy(document, inspectMetadata = expectedPages != null,
            requireNoMetadata = purgeMetadata, requireNoAttachments = purgeAttachments)
    }
}
