package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.json.JSONArray
import org.json.JSONObject
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream

object PdfFontInspectorEngineWorker {

    fun inspectFonts(sourceFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }
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

    fun extractEmbeddedFonts(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        var document: PDDocument? = null
        try {
            document = FileInputStream(sourceFd.fileDescriptor).use {
                PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings())
            }
            val fontBytesMap = mutableMapOf<String, ByteArray>()

            for (page in document.pages) {
                val res = page.resources ?: continue
                for (cosName in res.fontNames) {
                    val font = res.getFont(cosName) ?: continue
                    val streamPair = getEmbeddedFontStreamAndExt(font) ?: continue
                    val rawName = font.name ?: cosName.name
                    val isSubset = rawName.length > 7 && rawName.getOrNull(6) == '+'
                    val cleanName = if (isSubset) rawName.substring(7) else rawName
                    val ext = streamPair.second
                    val safeBaseName = cleanName.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_','.').take(96).ifEmpty { "font" }

                    val bytes = streamPair.first.createInputStream().use(::readBoundedFont)

                    if (bytes != null && bytes.isNotEmpty()) {
                        val alreadyStored = fontBytesMap.values.any { it.contentEquals(bytes) }
                        if (!alreadyStored) {
                            var targetName = "$safeBaseName$ext"
                            var counter = 2
                            while (fontBytesMap.containsKey(targetName)) {
                                targetName = "${safeBaseName}_$counter$ext"
                                counter++
                            }
                            require(fontBytesMap.size < 256) { "Too many embedded fonts" }
                            require(fontBytesMap.values.sumOf { it.size.toLong() } + bytes.size <= 128L * 1024 * 1024) { "Embedded font aggregate exceeds 128 MiB" }
                            fontBytesMap[targetName] = bytes
                        }
                    }
                }
            }

            if (fontBytesMap.isEmpty()) {
                throw IllegalStateException("No extractable embedded font files (.ttf, .otf, .pfb) found in document")
            }

            val outStream = com.pdfchemy.app.jail.boundedFileOutput(destFd.fileDescriptor)
            java.util.zip.ZipOutputStream(outStream).use { zos ->
                for ((entryName, bytes) in fontBytesMap) {
                    val entry = java.util.zip.ZipEntry(entryName)
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }

            val result = JSONObject()
            result.put("success", true)
            result.put("extractedCount", fontBytesMap.size)
            val namesArr = JSONArray()
            fontBytesMap.keys.forEach { namesArr.put(it) }
            result.put("fontFiles", namesArr)
            return result.toString()
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }


    private fun readBoundedFont(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(out.size().toLong() + count <= 32L * 1024 * 1024) { "Embedded font exceeds 32 MiB" }
            out.write(buffer,0,count)
        }
        return out.toByteArray()
    }

    private fun getEmbeddedFontStreamAndExt(font: PDFont): Pair<PDStream, String>? {
        val desc = (font as? PDType0Font)?.descendantFont?.fontDescriptor
            ?: font.fontDescriptor
            ?: return null

        val ff2 = desc.fontFile2
        if (ff2 != null) return Pair(ff2, ".ttf")
        val ff3 = desc.fontFile3
        if (ff3 != null) return Pair(ff3, ".otf")
        val ff = desc.fontFile
        if (ff != null) return Pair(ff, ".pfb")
        return null
    }

    private class MutableFontEntry(
        val rawName: String,
        val font: PDFont,
        val pages: MutableSet<Int> = mutableSetOf()
    )
}
