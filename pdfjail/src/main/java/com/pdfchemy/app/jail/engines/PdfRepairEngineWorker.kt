package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfRepairEngineWorker {

    fun diagnose(sourceFd: ParcelFileDescriptor): String {
        try {
            val buffer = ByteArrayOutputStream()
            FileInputStream(sourceFd.fileDescriptor).use { inp ->
                inp.copyTo(buffer)
            }
            val bytes = buffer.toByteArray()

            if (bytes.size < 10) {
                return JSONObject().apply {
                    put("hasValidHeader", false)
                    put("hasValidEof", false)
                    put("recoveredPages", 0)
                    put("isEncrypted", false)
                    put("issueSummary", "File is empty or too small")
                }.toString()
            }

            val headerStr = String(bytes.take(20).toByteArray(), Charsets.US_ASCII)
            val hasValidHeader = headerStr.contains("%PDF-")

            val tailStr = String(bytes.takeLast(100).toByteArray(), Charsets.US_ASCII)
            val hasValidEof = tailStr.contains("%%EOF")

            var pageCount = 0
            var isEncrypted = false
            val issueSummary = mutableListOf<String>()

            if (!hasValidHeader) issueSummary.add("Corrupted or missing %PDF header")
            if (!hasValidEof) issueSummary.add("Missing or truncated %%EOF marker")

            try {
                val doc = PDDocument.load(java.io.ByteArrayInputStream(bytes), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                pageCount = doc.numberOfPages
                isEncrypted = doc.isEncrypted
                doc.close()
            } catch (e: Exception) {
                issueSummary.add("Syntax or XRef table corruption: ${e.message?.take(60)}")
            }

            val summaryText = if (issueSummary.isEmpty()) "Standard PDF structure" else issueSummary.joinToString("; ")
            
            return JSONObject().apply {
                put("hasValidHeader", hasValidHeader)
                put("hasValidEof", hasValidEof)
                put("recoveredPages", pageCount)
                put("isEncrypted", isEncrypted)
                put("issueSummary", summaryText)
            }.toString()
        } catch (e: Exception) {
            return JSONObject().apply {
                put("error", e.message)
            }.toString()
        }
    }

    fun repair(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor): String {
        try {
            val buffer = ByteArrayOutputStream()
            FileInputStream(sourceFd.fileDescriptor).use { inp ->
                inp.copyTo(buffer)
            }
            var bytes = buffer.toByteArray()

            if (bytes.isEmpty()) {
                return JSONObject().put("error", "Source file is empty").toString()
            }

            val headerIndex = indexOfBytes(bytes, "%PDF-".toByteArray(Charsets.US_ASCII))
            if (headerIndex > 0) {
                bytes = bytes.copyOfRange(headerIndex, bytes.size)
            } else if (headerIndex < 0) {
                val header = "%PDF-1.7\n".toByteArray(Charsets.US_ASCII)
                bytes = header + bytes
            }

            val tailStr = String(bytes.takeLast(60).toByteArray(), Charsets.US_ASCII)
            if (!tailStr.contains("%%EOF")) {
                val eofBytes = "\n%%EOF\n".toByteArray(Charsets.US_ASCII)
                bytes = bytes + eofBytes
            }

            val doc = PDDocument.load(java.io.ByteArrayInputStream(bytes), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val recoveredPageCount = doc.numberOfPages

            FileOutputStream(targetFd.fileDescriptor).use { outStream ->
                doc.save(outStream)
            }
            doc.close()

            return JSONObject().apply {
                put("success", true)
                put("recoveredPages", recoveredPageCount)
            }.toString()
        } catch (e: Exception) {
            return JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }.toString()
        }
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        for (i in 0..source.size - target.size) {
            var found = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}
