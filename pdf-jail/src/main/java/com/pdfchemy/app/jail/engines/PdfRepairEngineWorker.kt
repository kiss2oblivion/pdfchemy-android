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
        var count = 0
        while (count < size) {
            val read = Os.read(fd.fileDescriptor, bytes, count, size - count)
            if (read <= 0) break
            count += read
        }
        return bytes.copyOf(count)
    }
    fun diagnose(sourceFd: ParcelFileDescriptor): String {
        val started = System.nanoTime()
        JailQuotas.enforceFileSize(sourceFd)
        val sourceBytes = sourceFd.statSize
        val xref = XrefDiagnostics.inspect(sourceBytes) { offset, count -> readAt(sourceFd, offset, count) }
        val prefix = String(readAt(sourceFd, 0, 1024), Charsets.US_ASCII)
        val suffix = String(readAt(sourceFd, (sourceBytes - 1024).coerceAtLeast(0), 1024), Charsets.US_ASCII)
        Os.lseek(sourceFd.fileDescriptor, 0, OsConstants.SEEK_SET)
        var pages = 0
        var encrypted = false
        var parserOpened = false
        var pooledObjects: Int? = null
        var issue = "Parser opened document; structural and security conformance not established"
        try {
            FileInputStream(sourceFd.fileDescriptor).use {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            }.use { document ->
                encrypted = document.isEncrypted
                pages = document.numberOfPages
                pooledObjects = document.document.objects.size
                parserOpened = true
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            encrypted = true
            issue = "Password required; structure not verified"
        } catch (e: Exception) {
            issue = "PDF parser could not complete diagnosis; exact cause undetermined"
            pages = 0
            pooledObjects = null
            parserOpened = false
        }
        return JSONObject().put("hasValidHeader", prefix.contains("%PDF-")).put("hasValidEof", suffix.contains("%%EOF"))
            .put("recoveredPages", pages).put("isEncrypted", encrypted).put("issueSummary", issue)
            .put("sourceBytes", sourceBytes).put("parserOpened", parserOpened)
            .put("xref", XrefDiagnostics.json(xref))
            .put("diagnosticElapsedMs", ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L))
            .apply { pooledObjects?.let { put("pooledObjects", it) } }.toString()
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
            }.use { publishRecovered(it, targetFd, requireVanguardClean,
                headerInserted = headerIndex < 0, prefixBytesRemoved = headerIndex.coerceAtLeast(0).toLong(),
                eofAppended = !suffix.contains("%%EOF")) }
        } finally { file.delete() }
    }

    private fun publishRecovered(document: PDDocument, targetFd: ParcelFileDescriptor, requireVanguardClean: Boolean,
                                 headerInserted: Boolean = false, prefixBytesRemoved: Long = 0L,
                                 eofAppended: Boolean = false): String {
        val pageTree = PageTreeRecovery.inspect(document, repairCounts = true)
        val pageCount = pageTree.pages
        check(document.numberOfPages == pageCount) { "Parser disagrees with reconstructed page hierarchy" }
        if (!requireVanguardClean) {
            boundedFileOutput(targetFd.fileDescriptor).use { document.save(it) }
            return JSONObject().put("success", true).put("recoveredPages", pageCount).toString()
        }
        // Verified recovery never quietly strips blocked content: sanitization
        // requires the user's separate consent. Reopen the actual candidate.
        check(!document.isEncrypted) { "Recovery requires an unencrypted PDF" }
        // Check input as well as output so structural repair cannot silently discard
        // a detectable active-content carrier during serialization.
        val inputFindings = ActiveContentScrubber.inspect(document)
        check(inputFindings.executableFindingCount() == 0) { "Input contains blocked active content; use sanitization" }
        val candidate = com.pdfchemy.app.jail.JailScratch.createTempFile("verified_repair_", ".pdf")
        try {
            boundedFileOutput(candidate).use { document.save(it) }
            com.pdfchemy.app.jail.CapabilityIo.input(candidate).use {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            }.use { PdfSanitizerEngineWorker.verifyRecoveredDocument(it, pageCount) }
            boundedFileOutput(targetFd.fileDescriptor).use { output ->
                com.pdfchemy.app.jail.CapabilityIo.input(candidate).use { it.copyTo(output) }
            }
            val method = if (headerInserted || prefixBytesRemoved > 0 || eofAppended) "NORMALIZE_CONTAINER" else "RESERIALIZE"
            return JSONObject().put("success", true).put("recoveredPages", pageCount)
                .put("recovery", recoveryAccounting(method, pageCount, headerInserted, prefixBytesRemoved, eofAppended,
                    pageTreeCountsCorrected = pageTree.correctedCounts)).toString()
        } finally { candidate.delete() }
    }
}
