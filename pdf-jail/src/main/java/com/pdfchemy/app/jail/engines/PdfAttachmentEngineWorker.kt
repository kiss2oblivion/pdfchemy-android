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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar

object PdfAttachmentEngineWorker {

    fun listAttachments(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val names = document.documentCatalog.names
            val embeddedFiles = names?.embeddedFiles

            val resultList = mutableListOf<JSONObject>()
            if (embeddedFiles != null) {
                val map = embeddedFiles.names ?: emptyMap()
                for ((key, spec) in map) {
                    if (spec is PDComplexFileSpecification) {
                        val fileName = spec.filename ?: key
                        val ef = spec.embeddedFile
                        val size = ef?.size?.toLong() ?: 0L
                        val mime = ef?.subtype ?: "application/octet-stream"
                        
                        val jo = JSONObject()
                        jo.put("name", fileName)
                        jo.put("sizeBytes", size)
                        jo.put("mimeType", mime)
                        resultList.add(jo)
                    }
                }
            }
            
            val jsonArray = JSONArray()
            for (item in resultList) {
                jsonArray.put(item)
            }
            return jsonArray.toString()
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    fun extractAttachment(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val attachmentName = params.getString("attachmentName")
        
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val names = document.documentCatalog.names
            val embeddedFiles = names?.embeddedFiles ?: throw IllegalStateException("No embedded files found")
            val map = embeddedFiles.names ?: emptyMap()
            var targetFileSpec: PDComplexFileSpecification? = null

            for ((key, spec) in map) {
                if (spec is PDComplexFileSpecification) {
                    if (spec.filename == attachmentName || key == attachmentName) {
                        targetFileSpec = spec
                        break
                    }
                }
            }

            val ef = targetFileSpec?.embeddedFile ?: throw IllegalStateException("Attachment not found")

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

    fun embedAttachment(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, extraFd: ParcelFileDescriptor, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val fileName = params.optString("fileName", "attachment.dat")
        val fileSize = params.optLong("fileSize", 0L)
        
        var document: PDDocument? = null
        var tempFile: File? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            
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

            tempFile = File.createTempFile("attached_", ".pdf")
            document.save(tempFile)
            document.close()
            document = null
            attachStream.close()

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                tempFile.inputStream().use { inp ->
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
        val attachmentName = params.getString("attachmentName")
        
        var document: PDDocument? = null
        var tempFile: File? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val names = document.documentCatalog.names
            val embeddedFiles = names?.embeddedFiles

            if (embeddedFiles != null) {
                val map = (embeddedFiles.names ?: emptyMap()).toMutableMap()
                val keyToRemove = map.keys.firstOrNull { key ->
                    val spec = map[key]
                    (spec is PDComplexFileSpecification && spec.filename == attachmentName) || key == attachmentName
                }

                if (keyToRemove != null) {
                    map.remove(keyToRemove)
                    embeddedFiles.setNames(map)
                }
            }

            tempFile = File.createTempFile("rm_attach_", ".pdf")
            document.save(tempFile)
            document.close()
            document = null

            FileOutputStream(targetFd.fileDescriptor).use { out ->
                tempFile.inputStream().use { inp ->
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
