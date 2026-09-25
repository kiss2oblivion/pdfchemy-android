package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.File

object PdfLinearizeEngineWorker {

    fun checkLinearized(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            val fis = FileInputStream(sourceFd.fileDescriptor)
            val channel = fis.channel
            val currentPos = channel.position()
            
            val headerBytes = ByteArray(2048)
            val bytesRead = fis.read(headerBytes)
            val headerString = if (bytesRead > 0) String(headerBytes, 0, bytesRead, Charsets.US_ASCII) else ""
            val isLinear = headerString.contains("/Linearized")
            
            // Restore channel position to read document
            channel.position(currentPos)
            document = PDDocument.load(fis, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val pageCount = document.numberOfPages

            val result = JSONObject()
            result.put("isLinearized", isLinear)
            result.put("pageCount", pageCount)
            return result.toString()
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    fun optimizeFastWebView(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            if (document.numberOfPages == 0) throw IllegalStateException("PDF has no pages")
            
            val tempFile = File.createTempFile("linear_worker", ".pdf")
            try {
                document.save(tempFile)
                val outBytes = tempFile.length()
                tempFile.inputStream().use { inp ->
                    FileOutputStream(destFd.fileDescriptor).use { out ->
                        inp.copyTo(out)
                    }
                }
                val result = JSONObject()
                result.put("outBytes", outBytes)
                return result.toString()
            } finally {
                tempFile.delete()
            }
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }
}
