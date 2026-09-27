package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream

object PdfFontInspectorEngineWorker {

    fun inspectFonts(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            val fontMap = mutableMapOf<String, MutableFontEntry>()

            for ((pageIdx, page) in document.pages.withIndex()) {
                val res = page.resources ?: continue
                for (cosName in res.fontNames) {
                    val font = res.getFont(cosName) ?: continue
                    val rawName = font.name ?: cosName.name
                    val entry = fontMap.getOrPut(rawName) { MutableFontEntry(rawName = rawName, font = font) }
                    entry.pages.add(pageIdx + 1)
                }
            }

            val arr = JSONArray()
            fontMap.values.sortedBy { it.rawName }.forEach { entry ->
                val raw = entry.rawName
                val isSubset = raw.length > 7 && raw.getOrNull(6) == '+'
                val cleanName = if (isSubset) raw.substring(7) else raw

                val fontSubtype = try {
                    entry.font.subType ?: entry.font.javaClass.simpleName
                } catch (_: Exception) {
                    entry.font.javaClass.simpleName
                }

                val formatType = when {
                    fontSubtype.contains("TrueType", ignoreCase = true) -> "TrueType"
                    fontSubtype.contains("Type1", ignoreCase = true) -> "Type 1"
                    fontSubtype.contains("Type0", ignoreCase = true) -> "Type 0 (Composite/CID)"
                    fontSubtype.contains("OpenType", ignoreCase = true) -> "OpenType"
                    else -> fontSubtype
                }

                val isEmbedded = try {
                    entry.font.isEmbedded || (entry.font.fontDescriptor?.fontFile != null ||
                            entry.font.fontDescriptor?.fontFile2 != null ||
                            entry.font.fontDescriptor?.fontFile3 != null)
                } catch (_: Exception) { false }

                val encoding = try {
                    entry.font.cosObject.getNameAsString("Encoding") ?: "Standard"
                } catch (_: Exception) { "Standard" }

                val obj = JSONObject()
                obj.put("postscriptName", raw)
                obj.put("familyName", cleanName)
                obj.put("formatType", formatType)
                obj.put("isEmbedded", isEmbedded)
                obj.put("isSubset", isSubset)
                obj.put("encoding", encoding)
                obj.put("pageCountUsed", entry.pages.size)
                arr.put(obj)
            }
            return arr.toString()
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    private class MutableFontEntry(
        val rawName: String,
        val font: PDFont,
        val pages: MutableSet<Int> = mutableSetOf()
    )
}
