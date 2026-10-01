package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONObject
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
import java.io.File

object PdfLinearizeEngineWorker {

    fun checkLinearized(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            val fis = FileInputStream(sourceFd.fileDescriptor)
            val currentPos = android.system.Os.lseek(sourceFd.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR)
            
            val headerBytes = ByteArray(2048)
            val bytesRead = fis.read(headerBytes)
            val headerString = if (bytesRead > 0) String(headerBytes, 0, bytesRead, Charsets.US_ASCII) else ""
            val isLinear = headerString.contains("/Linearized")
            
            // Restore channel position to read document
            android.system.Os.lseek(sourceFd.fileDescriptor, currentPos, android.system.OsConstants.SEEK_SET)
            document = PDDocument.load(fis, com.pdfchemy.app.jail.JailMemory.settings())
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
            document = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
            if (document.numberOfPages == 0) throw IllegalStateException("PDF has no pages")
            
            val tempFile = com.pdfchemy.app.jail.JailScratch.createTempFile("linear_worker", ".pdf")
            try {
                com.pdfchemy.app.jail.boundedFileOutput(tempFile).use { document.save(it) }
                val outBytes = tempFile.length()
                com.pdfchemy.app.jail.CapabilityIo.input(tempFile).use { inp ->
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
