package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.CapabilityIo
import com.pdfchemy.app.jail.JailMemory
import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONObject

object PdfLinearizeEngineWorker {
    const val UNSUPPORTED_MESSAGE = "Fast Web View generation is not supported by the local engine"

    fun checkLinearized(sourceFd: ParcelFileDescriptor): String =
        CapabilityIo.input(sourceFd.fileDescriptor).use { input ->
            PDDocument.load(input, JailMemory.settings()).use(::inspectDocument)
        }

    /** No structural linearization validator is bundled. A marker is never certification. */
    internal fun inspectDocument(document: PDDocument): String {
        require(document.numberOfPages in 1..SecurityLimits.MAX_PAGE_COUNT) { "Invalid PDF page count" }
        return JSONObject()
            .put("isLinearized", false) // Compatibility field: never an affirmative claim.
            .put("verificationStatus", "NOT_VERIFIED")
            .put("pageCount", document.numberOfPages)
            .toString()
    }

    fun optimizeFastWebView(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        // Reject before reading input, allocating scratch, or writing any output capability.
        throw UnsupportedOperationException(UNSUPPORTED_MESSAGE)
    }
}
