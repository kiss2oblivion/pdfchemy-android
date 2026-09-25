package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object PdfMetadataEngineWorker {
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun readMetadata(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val info = document.documentInformation
            val catalog = document.documentCatalog

            val creationStr = try { info?.creationDate?.let { dateFormatter.format(it.time) } ?: "" } catch (_: Exception) { "" }
            val modStr = try { info?.modificationDate?.let { dateFormatter.format(it.time) } ?: "" } catch (_: Exception) { "" }

            val obj = JSONObject()
            obj.put("title", info?.title ?: "")
            obj.put("author", info?.author ?: "")
            obj.put("subject", info?.subject ?: "")
            obj.put("keywords", info?.keywords ?: "")
            obj.put("creator", info?.creator ?: "")
            obj.put("producer", info?.producer ?: "")
            obj.put("creationDate", creationStr)
            obj.put("modificationDate", modStr)
            obj.put("pageCount", document.numberOfPages)
            obj.put("hasXmpMetadata", catalog?.metadata != null)
            obj.put("isEncrypted", document.isEncrypted)
            
            return obj.toString()
        } finally {
            try { document?.close() } catch(_: Exception){}
        }
    }

    fun writeOrSanitizeMetadata(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor, paramsJson: String): String {
        var document: PDDocument? = null
        try {
            val params = JSONObject(paramsJson)
            val wipeAllMetadata = params.optBoolean("wipeAllMetadata", false)
            
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())

            if (wipeAllMetadata) {
                val blankInfo = PDDocumentInformation()
                document.documentInformation = blankInfo
                document.documentCatalog?.metadata = null
                document.document.trailer.removeItem(com.tom_roush.pdfbox.cos.COSName.ID)
            } else if (params.has("newMetadata")) {
                val md = params.getJSONObject("newMetadata")
                var info = document.documentInformation
                if (info == null) {
                    info = PDDocumentInformation()
                    document.documentInformation = info
                }
                info.title = md.optString("title").ifBlank { null }
                info.author = md.optString("author").ifBlank { null }
                info.subject = md.optString("subject").ifBlank { null }
                info.keywords = md.optString("keywords").ifBlank { null }
                info.creator = md.optString("creator").ifBlank { null }
                info.producer = md.optString("producer").ifBlank { null }
                info.modificationDate = Calendar.getInstance()
            }

            document.save(FileOutputStream(destFd.fileDescriptor))
            return "{}"
        } finally {
            try { document?.close() } catch(_: Exception){}
        }
    }
}
