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

    internal fun listAttachments(document: PDDocument): String {
        val result = JSONArray()
        val files = document.documentCatalog.names?.embeddedFiles?.names.orEmpty()
        for ((key, spec) in files) {
            val ef = spec.embeddedFile
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
            val ef = resolveAttachment(document, params).embeddedFile ?: throw IllegalStateException("Attachment not found")

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
        return document.documentCatalog.names?.embeddedFiles?.names?.get(id)
            ?: throw IllegalArgumentException("Attachment not found")
    }

    internal fun removeAttachmentFromDocument(document: PDDocument, params: JSONObject) {
        val id = attachmentId(params)
        resolveAttachment(document, params) // Validate before changing the document.
        val tree = requireNotNull(document.documentCatalog.names?.embeddedFiles)
        tree.names = tree.names.orEmpty().toMutableMap().apply { remove(id) }
    }

    fun embedAttachment(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, extraFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val fileName = params.optString("fileName", "attachment.dat")
        val fileSize = params.optLong("fileSize", 0L)
        
        var document: PDDocument? = null
        var tempFile: File? = null
        try {
            document = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            
            val namesDict = document.documentCatalog.names ?: PDDocumentNameDictionary(document.documentCatalog).also {
                document.documentCatalog.names = it
            }

            val embeddedTree = namesDict.embeddedFiles ?: PDEmbeddedFilesNameTreeNode().also {
                namesDict.embeddedFiles = it
            }

            val currentMap = (embeddedTree.names ?: emptyMap()).toMutableMap()

            val fileSpec = PDComplexFileSpecification()
            fileSpec.file = fileName

            val attachStream = FileInputStream(extraFd.fileDescriptor)
            val embeddedFile = PDEmbeddedFile(document, attachStream)
            if (fileSize > 0) {
                embeddedFile.size = fileSize.toInt()
            }
            embeddedFile.creationDate = Calendar.getInstance()
            fileSpec.embeddedFile = embeddedFile

            currentMap[fileName] = fileSpec
            embeddedTree.setNames(currentMap)

            tempFile = com.pdfchemy.app.jail.JailScratch.createTempFile("attached_", ".pdf")
            com.pdfchemy.app.jail.boundedFileOutput(tempFile).use { document.save(it) }
            document.close()
            document = null
            attachStream.close()

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
