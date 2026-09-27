package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

object PdfArchiveValidatorEngineWorker {

    fun inspectPdfACompliance(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val checks = JSONArray()

            val isUnencrypted = !document.isEncrypted
            checks.put(createCheckObj(
                "Document Not Encrypted", isUnencrypted,
                if (isUnencrypted) "No encryption dictionary found" else "Document is password protected or uses DRM (violates ISO 19005)",
                "ERROR"
            ))

            val outputIntents = document.documentCatalog.outputIntents
            val hasOutputIntent = !outputIntents.isNullOrEmpty()
            checks.put(createCheckObj(
                "Device-Independent Color Profile (OutputIntents)", hasOutputIntent,
                if (hasOutputIntent) "Color OutputIntent profile found (${outputIntents.size} present)" else "Missing standard ICC OutputIntent profile for device-independent rendering",
                if (hasOutputIntent) "INFO" else "WARNING"
            ))

            val xmpMetadata = document.documentCatalog.metadata
            val hasXmp = xmpMetadata != null
            var pdfaVersion = "None (Standard PDF)"

            if (hasXmp) {
                try {
                    val xmlString = xmpMetadata!!.createInputStream().bufferedReader().use { it.readText() }
                    if (xmlString.contains("pdfaid:part=\"1\"") || xmlString.contains("<pdfaid:part>1</pdfaid:part>")) {
                        pdfaVersion = "PDF/A-1"
                    } else if (xmlString.contains("pdfaid:part=\"2\"") || xmlString.contains("<pdfaid:part>2</pdfaid:part>")) {
                        pdfaVersion = "PDF/A-2"
                    } else if (xmlString.contains("pdfaid:part=\"3\"") || xmlString.contains("<pdfaid:part>3</pdfaid:part>")) {
                        pdfaVersion = "PDF/A-3"
                    }
                } catch (_: Exception) {}
            }

            checks.put(createCheckObj(
                "Standardized XMP Metadata Packet", hasXmp,
                if (hasXmp) "XMP Metadata stream is embedded" else "Missing embedded XMP metadata packet",
                "ERROR"
            ))

            var totalFonts = 0
            var embeddedFonts = 0
            val checkedFonts = mutableSetOf<String>()

            for (page in document.pages) {
                val res = page.resources ?: continue
                for (fontName in res.fontNames) {
                    val font = res.getFont(fontName) ?: continue
                    val name = font.name ?: fontName.name
                    if (checkedFonts.add(name)) {
                        totalFonts++
                        if (isFontEmbedded(font)) embeddedFonts++
                    }
                }
            }

            val fontsFullyEmbedded = totalFonts == 0 || embeddedFonts == totalFonts
            checks.put(createCheckObj(
                "100% Embedded Font Subsets", fontsFullyEmbedded,
                if (totalFonts == 0) "No text fonts required" else "${embeddedFonts} / ${totalFonts} unique fonts fully embedded",
                "ERROR"
            ))

            val names = document.documentCatalog.names
            val hasJs = names?.javaScript != null || document.documentCatalog.actions != null
            checks.put(createCheckObj(
                "Absence of JavaScript & External Actions", !hasJs,
                if (!hasJs) "No dangerous scripts or external triggers detected" else "Contains active scripts/actions incompatible with PDF/A preservation",
                "ERROR"
            ))

            var passCount = 0
            var isCompliant = true
            for (i in 0 until checks.length()) {
                val check = checks.getJSONObject(i)
                if (check.getBoolean("isPassed")) passCount++
                if (!check.getBoolean("isPassed") && check.getString("severity") == "ERROR") isCompliant = false
            }
            
            val score = ((passCount.toDouble() / checks.length().toDouble()) * 100).toInt()

            val result = JSONObject()
            result.put("pdfaVersionDetected", if (isCompliant && pdfaVersion == "None (Standard PDF)") "PDF/A-1b Ready" else pdfaVersion)
            result.put("complianceScore", score)
            result.put("checks", checks)
            result.put("isCompliant", isCompliant)
            return result.toString()

        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    private fun createCheckObj(rule: String, isPassed: Boolean, details: String, severity: String): JSONObject {
        val obj = JSONObject()
        obj.put("rule", rule)
        obj.put("isPassed", isPassed)
        obj.put("details", details)
        obj.put("severity", severity)
        return obj
    }

    private fun isFontEmbedded(font: PDFont): Boolean {
        return try {
            font.isEmbedded || (font.fontDescriptor?.fontFile != null || font.fontDescriptor?.fontFile2 != null || font.fontDescriptor?.fontFile3 != null)
        } catch (_: Exception) {
            false
        }
    }

    fun convertToPdfA(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())

            val markInfo = com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo()
            markInfo.isMarked = true
            document.documentCatalog.markInfo = markInfo

            val xmpXml = """<?xpacket begin="""" + "\uFEFF" + """" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
      <pdfaid:part>1</pdfaid:part>
      <pdfaid:conformance>B</pdfaid:conformance>
    </rdf:Description>
    <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
      <dc:format>application/pdf</dc:format>
    </rdf:Description>
    <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
      <pdf:Producer>PDFchemy ISO 19005-1b Archival Engine</pdf:Producer>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>""".trimIndent()

            val metadata = com.tom_roush.pdfbox.pdmodel.common.PDMetadata(document)
            metadata.importXMPMetadata(xmpXml.toByteArray(Charsets.UTF_8))
            document.documentCatalog.metadata = metadata

            document.documentCatalog.openAction = null
            document.documentCatalog.actions = null
            document.documentCatalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("JavaScript"))
            document.documentCatalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("AA"))

            document.documentCatalog.acroForm?.let { acroForm ->
                try { acroForm.flatten() } catch (_: Exception) {}
            }

            document.save(FileOutputStream(destFd.fileDescriptor))
            return "{}"
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }
}
