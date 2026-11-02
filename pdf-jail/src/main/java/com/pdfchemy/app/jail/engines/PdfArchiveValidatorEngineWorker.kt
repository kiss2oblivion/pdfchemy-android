package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.CapabilityIo
import com.pdfchemy.app.jail.JailMemory
import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

object PdfArchiveValidatorEngineWorker {
    fun inspectPdfACompliance(sourceFd: ParcelFileDescriptor): String =
        CapabilityIo.input(sourceFd.fileDescriptor).use { input ->
            PDDocument.load(input, JailMemory.settings()).use(::inspectPreliminaryChecks)
        }

    /** Presence checks only. None establishes ISO 19005 conformance. */
    internal fun inspectPreliminaryChecks(document: PDDocument): String {
        require(document.numberOfPages in 1..SecurityLimits.MAX_PAGE_COUNT) { "Invalid PDF page count" }
        val checks = JSONArray()
        fun check(rule: String, passed: Boolean, details: String) {
            checks.put(JSONObject().put("rule", rule).put("isPassed", passed)
                .put("details", details).put("severity", if (passed) "INFO" else "WARNING"))
        }
        check("Encryption", !document.isEncrypted,
            if (document.isEncrypted) "Encryption is present" else "No encryption found in this check")
        val profilePresent = document.documentCatalog.outputIntents.any {
            (it.cosObject as? COSDictionary)?.getDictionaryObject(COSName.getPDFName("DestOutputProfile")) is COSStream
        }
        check("Output profile stream", profilePresent,
            if (profilePresent) "Profile stream present; profile contents and color use are not validated"
            else "No embedded output profile stream found")
        val metadataPresent = document.documentCatalog.metadata != null
        check("Metadata stream", metadataPresent,
            if (metadataPresent) "Metadata stream present; XMP schema and PDF/A declarations are not validated"
            else "No metadata stream found")
        var totalFonts = 0
        var embeddedFonts = 0
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        for (page in document.pages) {
            val resources = page.resources ?: continue
            for (name in resources.fontNames) {
                val font = resources.getFont(name) ?: continue
                if (seen.add(font.cosObject)) {
                    totalFonts++
                    if (font.isEmbedded) embeddedFonts++
                }
            }
        }
        check("Referenced page-font embedding", totalFonts == embeddedFonts,
            "$embeddedFonts of $totalFonts checked page fonts report embedding; font programs and nested resources are not validated")
        // Read dictionary entries directly: convenience getters may create empty action dictionaries.
        val catalog = document.documentCatalog.cosObject
        val names = catalog.getDictionaryObject(COSName.getPDFName("Names")) as? COSDictionary
        val hasCatalogActions = catalog.getDictionaryObject(COSName.getPDFName("OpenAction")) != null ||
            catalog.getDictionaryObject(COSName.getPDFName("AA")) != null ||
            names?.getDictionaryObject(COSName.getPDFName("JavaScript")) != null
        check("Catalog action entries", !hasCatalogActions,
            if (hasCatalogActions) "Catalog action entries found; full validation is required"
            else "No catalog action entries found in this preliminary check")
        val passed = (0 until checks.length()).count { checks.getJSONObject(it).getBoolean("isPassed") }
        return JSONObject()
            .put("pdfaVersionDetected", "Unknown (not certified)")
            .put("complianceScore", passed * 100 / checks.length()) // Legacy limited-check pass rate only.
            .put("checks", checks)
            .put("isCompliant", false)
            .put("certificationStatus", "NOT_CERTIFIED")
            .put("assessmentScope", "PRELIMINARY_PDF_A_CHECKS")
            .toString()
    }

    fun convertToPdfA(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        // Metadata tagging is not conversion. Do not access either capability or create output.
        throw UnsupportedOperationException("PDF/A conversion is not supported by the local engine")
    }
}
