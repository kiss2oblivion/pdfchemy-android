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
    fun audit(sourceFd: ParcelFileDescriptor): String = try {
        FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, "", com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
            val f = ActiveContentScrubber.inspect(document)
            val metadata = PdfPrivacyCleanup.hasMetadata(document)
            val executableThreats = f.javascript + f.launches + f.actions + f.untrustedUris
            JSONObject().put("threatsFound", executableThreats).put("isClean", executableThreats == 0)
                .put("jsCount", f.javascript).put("launchActionsCount", f.launches).put("otherActionsCount", f.actions).put("untrustedUriCount", f.untrustedUris).put("attachmentCount", f.attachments).put("uriCount", f.uris)
                .put("hasMetadata", metadata).put("isEncrypted", document.isEncrypted).put("parseFailed", false).toString()
        }
    } catch (e: Exception) {
        val isEncrypted = e is InvalidPasswordException
        JSONObject()
            .put("threatsFound", 1)
            .put("isClean", false)
            .put("jsCount", 0)
            .put("launchActionsCount", 0)
            .put("otherActionsCount", 0)
            .put("untrustedUriCount", 0)
            .put("attachmentCount", 0)
            .put("uriCount", 0)
            .put("hasMetadata", false)
            .put("isEncrypted", isEncrypted)
            .put("parseFailed", !isEncrypted)
            .toString()
    }
    fun sanitize(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val p = JSONObject(paramsJson)
        val purgeJs = p.optBoolean("purgeJs", true)
        val purgeActions = p.optBoolean("purgeActions", true)
        val purgeAttachments = p.optBoolean("purgeAttachments", true)
        val purgeMetadata = p.optBoolean("purgeMetadata", true)
        val scratch = com.pdfchemy.app.jail.JailScratch.createTempFile("sanitized_", ".pdf")
        try {
            val removed = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
                val findings = cleanDocument(document, purgeJs, purgeActions, purgeMetadata, purgeAttachments)
                boundedFileOutput(scratch).use { document.save(it) }
                findings.selected(purgeJs, purgeActions, purgeAttachments)
            }
            com.pdfchemy.app.jail.CapabilityIo.input(scratch).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
                validateCleanDocument(document, purgeJs, purgeActions, purgeMetadata, purgeAttachments)
            }
            boundedFileOutput(targetFd.fileDescriptor).use { output -> com.pdfchemy.app.jail.CapabilityIo.input(scratch).use { it.copyTo(output) } }
            return JSONObject().put("isSuccess", true).put("threatsRemoved", removed.total).put("jsRemoved", removed.javascript)
                .put("actionsRemoved", removed.launches + removed.actions + removed.untrustedUris + removed.uris).put("attachmentsRemoved", removed.attachments).put("metadataRemoved", purgeMetadata).toString()
        } finally { scratch.delete() }
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
                                       purgeMetadata: Boolean, purgeAttachments: Boolean) {
        require(ActiveContentScrubber.inspect(document).selected(purgeJs, purgeActions, purgeAttachments).total == 0) { "Prohibited carriers survived sanitization" }
        if (purgeMetadata) require(!PdfPrivacyCleanup.hasMetadata(document)) { "Metadata survived sanitization" }
        if (purgeAttachments) require(!PdfPrivacyCleanup.hasAttachmentPayloads(document)) { "Attachment payload survived sanitization" }
    }
}
