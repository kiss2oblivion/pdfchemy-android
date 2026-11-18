package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
import java.util.Calendar
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.io.InputStream
import com.tom_roush.pdfbox.cos.*
import com.pdfchemy.app.security.SecurityLimits

object PdfAttachmentEngineWorker {

    fun listAttachments(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            return listAttachments(document)
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    private const val MAX_ATTACHMENTS = 500 // Matches the IPC attachment inventory contract.
    private fun name(value: String) = COSName.getPDFName(value)
    private fun value(raw: COSBase?): COSBase? {
        var result = raw
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        while (result is COSObject) {
            require(seen.add(result) && seen.size <= SecurityLimits.MAX_GRAPH_DEPTH) { "Invalid attachment reference" }
            result = result.`object`
        }
        return result
    }

    /** Do not trust /Limits or PDFBox's recursive lookup on an untrusted name tree. */
    private fun attachmentEntries(document: PDDocument): LinkedHashMap<String, PDComplexFileSpecification> {
        val root = document.documentCatalog.names?.embeddedFiles?.cosObject ?: return linkedMapOf()
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val entries = linkedMapOf<String, PDComplexFileSpecification>()
        fun visit(raw: COSBase?, depth: Int) {
            val node = value(raw)
            require(node is COSDictionary && seen.add(node) && seen.size <= SecurityLimits.MAX_GRAPH_NODES &&
                depth <= SecurityLimits.MAX_GRAPH_DEPTH) { "Invalid or excessive attachment name tree" }
            require(node.getCOSName(COSName.TYPE) !in setOf(COSName.PAGE, COSName.PAGES, COSName.CATALOG)) { "Attachment tree aliases document structure" }
            val names = value(node.getItem(COSName.NAMES)).takeUnless { it is COSNull }
            val kids = value(node.getItem(COSName.KIDS)).takeUnless { it is COSNull }
            require(names == null || kids == null) { "Attachment node contains both names and children" }
            if (names != null) {
                require(names is COSArray && names.size() % 2 == 0 && names.size() <= MAX_ATTACHMENTS * 2) { "Invalid attachment leaf" }
                for (index in 0 until names.size() step 2) {
                    val key = value(names.get(index))
                    val spec = value(names.get(index + 1))
                    require(key is COSString && spec is COSDictionary) { "Invalid attachment entry" }
                    val id = key.string
                    require(id.length <= 1024) { "Attachment identifier quota exceeded" }
                    require(!entries.containsKey(id) && entries.size < MAX_ATTACHMENTS) { "Duplicate or excessive attachment identifiers" }
                    entries[id] = PDComplexFileSpecification(spec)
                }
            } else if (kids != null) {
                require(kids is COSArray && kids.size() <= SecurityLimits.MAX_GRAPH_NODES) { "Invalid attachment children" }
                for (index in 0 until kids.size()) visit(kids.get(index), depth + 1)
            }
        }
        visit(root, 0)
        return entries
    }

    internal fun embeddedPayload(spec: PDComplexFileSpecification): PDEmbeddedFile? =
        spec.embeddedFileUnicode ?: spec.embeddedFile ?: spec.embeddedFileDos ?: spec.embeddedFileMac ?: spec.embeddedFileUnix

    private fun replaceEntries(document: PDDocument, entries: Map<String, PDComplexFileSpecification>) {
        val dictionary = document.documentCatalog.names ?: PDDocumentNameDictionary(document.documentCatalog).also {
            document.documentCatalog.names = it
        }
        val old = dictionary.embeddedFiles
        val replacement = PDEmbeddedFilesNameTreeNode()
        // Replace the inventory root, never mutate a child that may have other references.
        old?.cosObject?.keySet()?.forEach { key ->
            if (key !in setOf(COSName.NAMES, COSName.KIDS, COSName.LIMITS)) replacement.cosObject.setItem(key, old.cosObject.getItem(key))
        }
        replacement.names = entries.toSortedMap()
        dictionary.embeddedFiles = replacement
    }

    internal fun addAttachmentToDocument(document: PDDocument, fileName: String, input: InputStream, size: Long): String {
        val entries = attachmentEntries(document)
        require(entries.size < MAX_ATTACHMENTS && size in 0..SecurityLimits.MAX_PDF_FILESIZE) { "Attachment quota exceeded" }
        SecurityLimits.enforceStringLength(fileName, SecurityLimits.MAX_METADATA_LENGTH, "Attachment filename")
        var key = fileName.ifBlank { "attachment.dat" }
        if (key.length > 1024 || entries.containsKey(key)) {
            do { key = "attachment-${UUID.randomUUID()}" } while (entries.containsKey(key))
        }
        val embedded = PDEmbeddedFile(document, input).apply { this.size = size.toInt(); creationDate = Calendar.getInstance() }
        entries[key] = PDComplexFileSpecification().apply { file = fileName.ifBlank { "attachment.dat" }; embeddedFile = embedded }
        replaceEntries(document, entries)
        return key
    }

    internal fun listAttachments(document: PDDocument): String {
        val result = JSONArray()
        val files = attachmentEntries(document)
        for ((key, spec) in files) {
            val ef = embeddedPayload(spec)
            result.put(JSONObject()
                .put("attachmentId", key)
                .put("name", spec.filename ?: key)
                // PDF /Params/Size is optional; PDFBox uses -1 when it is unknown.
                .put("sizeBytes", ef?.size?.toLong()?.coerceAtLeast(0L) ?: 0L)
                .put("mimeType", ef?.subtype ?: "application/octet-stream"))
        }
        return result.toString()
    }

    fun extractAttachment(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        var document: PDDocument? = null
        try {
            document = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            val ef = embeddedPayload(resolveAttachment(document, params)) ?: throw IllegalStateException("Attachment not found")

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                ef.createInputStream().use { ins ->
                    ins.copyTo(out)
                }
            }
            return "{}"
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    private fun attachmentId(params: JSONObject): String {
        val id = params.get("attachmentId")
        require(id is String && id.length <= 1024) { "Invalid attachment identifier" }
        return id
    }

    internal fun resolveAttachment(document: PDDocument, params: JSONObject): PDComplexFileSpecification {
        val id = attachmentId(params)
        return attachmentEntries(document)[id] ?: throw IllegalArgumentException("Attachment not found")
    }

    internal fun removeAttachmentFromDocument(document: PDDocument, params: JSONObject) {
        val entries = attachmentEntries(document)
        require(entries.remove(attachmentId(params)) != null) { "Attachment not found" }
        replaceEntries(document, entries)
    }

    fun embedAttachment(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, extraFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val fileName = params.optString("fileName", "attachment.dat")
        
        var document: PDDocument? = null
        var tempFile: File? = null
        try {
            document = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            
            FileInputStream(extraFd.fileDescriptor).use { input ->
                // The staged FD identity/size was verified at admission; provider metadata is not authoritative.
                addAttachmentToDocument(document, fileName, input, extraFd.statSize)
            }

            tempFile = com.pdfchemy.app.jail.JailScratch.createTempFile("attached_", ".pdf")
            com.pdfchemy.app.jail.boundedFileOutput(tempFile).use { document.save(it) }
            document.close()
            document = null

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                com.pdfchemy.app.jail.CapabilityIo.input(tempFile).use { inp ->
                    inp.copyTo(out)
                }
            }
            return "{}"
        } finally {
            try { document?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    fun removeAttachment(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        
        var document: PDDocument? = null
        var tempFile: File? = null
        try {
            document = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            removeAttachmentFromDocument(document, params)

            tempFile = com.pdfchemy.app.jail.JailScratch.createTempFile("rm_attach_", ".pdf")
            com.pdfchemy.app.jail.boundedFileOutput(tempFile).use { document.save(it) }
            document.close()
            document = null

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                com.pdfchemy.app.jail.CapabilityIo.input(tempFile).use { inp ->
                    inp.copyTo(out)
                }
            }
            return "{}"
        } finally {
            try { document?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }
}
