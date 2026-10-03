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
            val info = document.documentInformation
            val metadata = info != null && (!info.author.isNullOrBlank() || !info.title.isNullOrBlank() || !info.creator.isNullOrBlank())
            JSONObject().put("threatsFound", f.total + if (metadata) 1 else 0).put("isClean", f.total == 0 && !metadata)
                .put("jsCount", f.javascript).put("launchActionsCount", f.actions).put("attachmentCount", f.attachments).put("uriCount", f.uris)
                .put("hasMetadata", metadata).put("isEncrypted", document.isEncrypted).put("parseFailed", false).toString()
        }
    } catch (e: Exception) {
        val isEncrypted = e is InvalidPasswordException
        JSONObject()
            .put("threatsFound", 1)
            .put("isClean", false)
            .put("jsCount", 0)
            .put("launchActionsCount", 0)
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
                val findings = ActiveContentScrubber.inspect(document, true, purgeJs, purgeActions, purgeAttachments)
                if (purgeMetadata) { document.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation(); document.documentCatalog.metadata = null }
                boundedFileOutput(scratch).use { document.save(it) }
                findings.selected(purgeJs, purgeActions, purgeAttachments)
            }
            com.pdfchemy.app.jail.CapabilityIo.input(scratch).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
                require(ActiveContentScrubber.inspect(document).selected(purgeJs, purgeActions, purgeAttachments).total == 0) { "Prohibited carriers survived sanitization" }
                if (purgeMetadata) require(document.documentInformation.cosObject.size() == 0 && document.documentCatalog.metadata == null) { "Metadata survived sanitization" }
            }
            boundedFileOutput(targetFd.fileDescriptor).use { output -> com.pdfchemy.app.jail.CapabilityIo.input(scratch).use { it.copyTo(output) } }
            return JSONObject().put("isSuccess", true).put("threatsRemoved", removed.total).put("jsRemoved", removed.javascript)
                .put("actionsRemoved", removed.actions + removed.uris).put("attachmentsRemoved", removed.attachments).put("metadataRemoved", purgeMetadata).toString()
        } finally { scratch.delete() }
    }
}
