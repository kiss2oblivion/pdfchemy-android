package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import org.json.JSONObject
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import java.io.File
import com.pdfchemy.app.jail.boundedFileOutput

object PdfRepairEngineWorker {
    private fun readAt(fd: ParcelFileDescriptor, offset: Long, size: Int): ByteArray {
        Os.lseek(fd.fileDescriptor, offset, OsConstants.SEEK_SET)
        val bytes = ByteArray(size)
        val count = Os.read(fd.fileDescriptor, bytes, 0, size)
        return bytes.copyOf(count.coerceAtLeast(0))
    }
    fun diagnose(sourceFd: ParcelFileDescriptor): String {
        JailQuotas.enforceFileSize(sourceFd)
        val prefix = String(readAt(sourceFd, 0, 1024), Charsets.US_ASCII)
        val suffix = String(readAt(sourceFd, (sourceFd.statSize - 1024).coerceAtLeast(0), 1024), Charsets.US_ASCII)
        Os.lseek(sourceFd.fileDescriptor, 0, OsConstants.SEEK_SET)
        var pages = 0; var encrypted = false
        var issue = "Standard PDF structure"
        try {
            FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { pages = it.numberOfPages; encrypted = it.isEncrypted }
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            encrypted = true; issue = "Password required; structure not verified"
        } catch (e: Exception) { issue = "PDF parser could not complete diagnosis; exact cause undetermined" }
        return JSONObject().put("hasValidHeader", prefix.contains("%PDF-")).put("hasValidEof", suffix.contains("%%EOF"))
            .put("recoveredPages", pages).put("isEncrypted", encrypted).put("issueSummary", issue).toString()
    }
    fun repair(sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, requireVanguardClean: Boolean = false): String {
        JailQuotas.enforceFileSize(sourceFd)
        val prefix = String(readAt(sourceFd, 0, 64 * 1024), Charsets.ISO_8859_1)
        val suffix = String(readAt(sourceFd, (sourceFd.statSize - 1024).coerceAtLeast(0), 1024), Charsets.US_ASCII)
        val headerIndex = prefix.indexOf("%PDF-")
        val file = com.pdfchemy.app.jail.JailScratch.createTempFile("repair_", ".pdf")
        try {
            boundedFileOutput(file).use { output ->
                if (headerIndex < 0) output.write("%PDF-1.7\n".toByteArray())
                Os.lseek(sourceFd.fileDescriptor, headerIndex.coerceAtLeast(0).toLong(), OsConstants.SEEK_SET)
                ParcelFileDescriptor.AutoCloseInputStream(sourceFd.dup()).use { it.copyTo(output) }
                if (!suffix.contains("%%EOF")) output.write("\n%%EOF\n".toByteArray())
            }
            com.pdfchemy.app.jail.CapabilityIo.input(file).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
                if (!requireVanguardClean) {
                    boundedFileOutput(targetFd.fileDescriptor).use { document.save(it) }
                    return JSONObject().put("success", true).put("recoveredPages", document.numberOfPages).toString()
                }
                // Recovery invoked by Vanguard must verify the rebuilt candidate BEFORE any user output.
                check(!document.isEncrypted && document.numberOfPages > 0) { "Recovery requires readable unencrypted pages" }
                val pageCount = document.numberOfPages
                val candidate = com.pdfchemy.app.jail.JailScratch.createTempFile("verified_repair_", ".pdf")
                try {
                    boundedFileOutput(candidate).use { document.save(it) }
                    com.pdfchemy.app.jail.CapabilityIo.input(candidate).use {
                        PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
                    }.use { rebuilt ->
                        check(!rebuilt.isEncrypted && rebuilt.numberOfPages == pageCount) { "Recovered page count not verified" }
                        val findings = ActiveContentScrubber.inspect(rebuilt)
                        check(findings.javascript == 0 && findings.launches == 0 && findings.actions == 0 &&
                            findings.untrustedUris == 0) { "Recovered PDF contains blocked active content" }
                        PdfPrivacyCleanup.hasMetadata(rebuilt) // same inspection budget as Vanguard
                    }
                    boundedFileOutput(targetFd.fileDescriptor).use { output ->
                        com.pdfchemy.app.jail.CapabilityIo.input(candidate).use { it.copyTo(output) }
                    }
                    return JSONObject().put("success", true).put("recoveredPages", pageCount).toString()
                } finally { candidate.delete() }
            }
        } finally { file.delete() }
    }
}
