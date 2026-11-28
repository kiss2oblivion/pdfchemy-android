package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONObject
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
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
        // Prefer parsing the unchanged bytes. Rewriting a prefix shifts absolute
        // object offsets; it is a last-resort normalization, not a first step.
        val original = try {
            FileInputStream(sourceFd.fileDescriptor).use {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            }
        } catch (password: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw password
        } catch (readFailure: java.io.IOException) {
            null
        }
        if (original != null) {
            return original.use { publishRecovered(it, targetFd, requireVanguardClean) }
        }

        val prefix = String(readAt(sourceFd, 0, 64 * 1024), Charsets.ISO_8859_1)
        val suffix = String(readAt(sourceFd, (sourceFd.statSize - 1024).coerceAtLeast(0), 1024), Charsets.US_ASCII)
        val headerIndex = prefix.indexOf("%PDF-")
        check(headerIndex != 0 || !suffix.contains("%%EOF")) {
            "PDF parsing failed; no supported container normalization is applicable"
        }
        val file = com.pdfchemy.app.jail.JailScratch.createTempFile("repair_", ".pdf")
        try {
            boundedFileOutput(file).use { output ->
                if (headerIndex < 0) output.write("%PDF-1.7\n".toByteArray())
                Os.lseek(sourceFd.fileDescriptor, headerIndex.coerceAtLeast(0).toLong(), OsConstants.SEEK_SET)
                ParcelFileDescriptor.AutoCloseInputStream(sourceFd.dup()).use { it.copyTo(output) }
                if (!suffix.contains("%%EOF")) output.write("\n%%EOF\n".toByteArray())
            }
            return com.pdfchemy.app.jail.CapabilityIo.input(file).use {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            }.use { publishRecovered(it, targetFd, requireVanguardClean) }
        } finally { file.delete() }
    }

    private fun publishRecovered(document: PDDocument, targetFd: ParcelFileDescriptor, requireVanguardClean: Boolean): String {
        val pageCount = document.numberOfPages
        check(pageCount in 1..com.pdfchemy.app.security.SecurityLimits.MAX_PAGE_COUNT) {
            "No supported readable pages recovered"
        }
        if (!requireVanguardClean) {
            boundedFileOutput(targetFd.fileDescriptor).use { document.save(it) }
            return JSONObject().put("success", true).put("recoveredPages", pageCount).toString()
        }
        // Verified recovery never quietly strips blocked content: sanitization
        // requires the user's separate consent. Reopen the actual candidate.
        check(!document.isEncrypted) { "Recovery requires an unencrypted PDF" }
        val candidate = com.pdfchemy.app.jail.JailScratch.createTempFile("verified_repair_", ".pdf")
        try {
            boundedFileOutput(candidate).use { document.save(it) }
            com.pdfchemy.app.jail.CapabilityIo.input(candidate).use {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            }.use { PdfSanitizerEngineWorker.verifyRecoveredDocument(it, pageCount) }
            boundedFileOutput(targetFd.fileDescriptor).use { output ->
                com.pdfchemy.app.jail.CapabilityIo.input(candidate).use { it.copyTo(output) }
            }
            return JSONObject().put("success", true).put("recoveredPages", pageCount).toString()
        } finally { candidate.delete() }
    }
}
