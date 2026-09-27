package com.pdfchemy.app.jail.engines

import android.content.Context
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object PdfOutlineReaderWorker {

    fun readOutline(context: Context, sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor?, paramsJson: String): String {
        val params = JSONObject(paramsJson)
        val isEpub = params.optBoolean("isEpub", false)

        val resultArr = if (isEpub) {
            val chapters = extractEpubChapters(context, sourceFd)
            val arr = JSONArray()
            for (chapter in chapters) {
                val chapObj = JSONObject()
                chapObj.put("title", chapter.first)
                val pArr = JSONArray()
                for (p in chapter.second) pArr.put(p)
                chapObj.put("paragraphs", pArr)
                arr.put(chapObj)
            }
            arr
        } else {
            var document: PDDocument? = null
            try {
                document = PDDocument.load(FileInputStream(sourceFd.fileDescriptor), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                val outline = document.documentCatalog.documentOutline
                val pageIndexMap = buildPageIndexMap(document)
                val visitedNodes = mutableSetOf<Int>()
                val arr = JSONArray()
                if (outline != null) {
                    val nodes = parseOutlineNode(document, outline, pageIndexMap, visitedNodes, 0)
                    for (node in nodes) {
                        arr.put(node)
                    }
                }
                arr
            } finally {
                try { document?.close() } catch(_: Exception){}
            }
        }
        
        val resultString = resultArr.toString()
        if (destFd != null) {
            try {
                java.io.FileOutputStream(destFd.fileDescriptor).use { output ->
                    output.write(resultString.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                // fallback
            }
            return "{}" // return dummy success string via Binder to save memory
        }
        return JailQuotas.enforceResultSize(resultString)
    }

    private fun buildPageIndexMap(document: PDDocument): Map<COSDictionary, Int> {
        val map = mutableMapOf<COSDictionary, Int>()
        var count = 0
        for (page in document.pages) {
            map[page.cosObject] = count
            count++
        }
        return map
    }

    private fun parseOutlineNode(
        doc: PDDocument, 
        node: PDOutlineNode, 
        pageIndexMap: Map<COSDictionary, Int>, 
        visitedNodes: MutableSet<Int>, 
        depth: Int
    ): List<JSONObject> {
        if (depth > 20 || visitedNodes.size > JailQuotas.MAX_BOOKMARKS) return emptyList()

        val result = mutableListOf<JSONObject>()
        var currentItem = node.firstChild
        
        while (currentItem != null) {
            val nodeId = System.identityHashCode(currentItem)
            if (visitedNodes.contains(nodeId)) {
                currentItem = currentItem.nextSibling
                continue
            }
            visitedNodes.add(nodeId)
            
            if (visitedNodes.size > JailQuotas.MAX_BOOKMARKS) {
                break // Stop processing to enforce quota
            }

            val title = currentItem.title ?: "Untitled"
            var pageIndex = 0
            try {
                val page = currentItem.findDestinationPage(doc)
                if (page != null) {
                    pageIndex = pageIndexMap[page.cosObject] ?: 0
                }
            } catch (_: Exception) {}

            val children = if (currentItem.hasChildren()) {
                parseOutlineNode(doc, currentItem, pageIndexMap, visitedNodes, depth + 1)
            } else {
                emptyList()
            }

            val obj = JSONObject()
            obj.put("title", title)
            obj.put("pageNumber", (pageIndex + 1).coerceAtLeast(1))
            if (children.isNotEmpty()) {
                val cArr = JSONArray()
                for (c in children) cArr.put(c)
                obj.put("children", cArr)
            }
            result.add(obj)
            currentItem = currentItem.nextSibling
        }
        return result
    }

    private fun extractEpubChapters(context: Context, sourceFd: ParcelFileDescriptor): List<Pair<String, List<String>>> {
        val chapters = mutableListOf<Pair<String, List<String>>>()
        var tempFile: File? = null
        var zip: ZipFile? = null
        var totalTextBytes = 0
        var totalChapters = 0

        try {
            tempFile = File.createTempFile("epub_worker_", ".epub", context.cacheDir)
            FileInputStream(sourceFd.fileDescriptor).use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }

            zip = ZipFile(tempFile)
            var opfPath = "OEBPS/content.opf"
            val containerEntry = zip.getEntry("META-INF/container.xml")
            if (containerEntry != null) {
                val containerText = readZipEntrySafely(zip, containerEntry, 10_000)
                val match = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerText)
                if (match != null) opfPath = match.groupValues[1]
            }

            val opfEntry = zip.getEntry(opfPath)
            val opfBaseUri = java.net.URI.create("file:///" + opfPath.replace(" ", "%20"))
            val spineItems = mutableListOf<String>()

            if (opfEntry != null) {
                val opfText = readZipEntrySafely(zip, opfEntry, 1_000_000) // Max 1MB for OPF
                val itemMap = mutableMapOf<String, String>()
                val itemRegex = Regex("""<(?:opf:)?item\s+([^>]+)>""", RegexOption.IGNORE_CASE)
                for (m in itemRegex.findAll(opfText)) {
                    val attrs = m.groupValues[1]
                    val idMatch = Regex("""id\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)
                    val hrefMatch = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(attrs)
                    if (idMatch != null && hrefMatch != null) {
                        val id = idMatch.groupValues[1]
                        var href = hrefMatch.groupValues[1]
                        href = href.substringBefore('#')
                        href = java.net.URLDecoder.decode(href, "UTF-8")
                        val resolvedUri = opfBaseUri.resolve(href.replace(" ", "%20"))
                        var resolvedPath = resolvedUri.path
                        if (resolvedPath.startsWith("/")) resolvedPath = resolvedPath.substring(1)
                        resolvedPath = java.net.URLDecoder.decode(resolvedPath, "UTF-8")
                        itemMap[id] = resolvedPath
                    }
                }

                val itemrefRegex = Regex("""<(?:opf:)?itemref\s+[^>]*idref\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
                for (m in itemrefRegex.findAll(opfText)) {
                    val resolved = itemMap[m.groupValues[1]]
                    if (resolved != null && (resolved.endsWith(".xhtml", true) || resolved.endsWith(".html", true) || resolved.endsWith(".htm", true))) {
                        if (!resolved.contains("nav.xhtml", true) && !resolved.contains("toc.xhtml", true)) {
                            spineItems.add(resolved)
                        }
                    }
                }
            }

            if (spineItems.isEmpty()) {
                val allHtml = zip.entries().toList().filter { 
                    it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) 
                }.filter { !it.name.contains("nav.xhtml", true) && !it.name.contains("toc.xhtml", true) }.sortedBy { it.name }
                for (entry in allHtml) spineItems.add(entry.name)
            }

            for ((idx, path) in spineItems.withIndex()) {
                if (totalChapters >= JailQuotas.MAX_BOOKMARKS) break // Use Bookmark limit for chapters
                if (totalTextBytes >= JailQuotas.MAX_TEXT_BYTES) break // Stop extracting if we hit global text limit
                
                val entry = zip.getEntry(path) ?: continue
                
                // Estimate output size to prevent reading giant zip entries
                val remainingBytes = JailQuotas.MAX_TEXT_BYTES - totalTextBytes
                val html = readZipEntrySafely(zip, entry, remainingBytes * 2) // * 2 because HTML tags will be stripped
                
                val titleMatch = Regex("""<(?:h[1-3]|title)[^>]*>(.*?)</(?:h[1-3]|title)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)
                var chapTitle = titleMatch?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), "")?.trim() ?: "Chapter "
                chapTitle = decodeHtmlEntities(chapTitle)

                var clean = html.replace(Regex("""<head.*?</head>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                clean = clean.replace(Regex("""<style.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                clean = clean.replace(Regex("""<script.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
                clean = clean.replace(Regex("""<(p|h[1-6]|div|li|tr)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
                clean = clean.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                clean = clean.replace(Regex("""<[^>]+>"""), "")
                clean = decodeHtmlEntities(clean)
                
                var chapterBytes = 0
                val paragraphs = clean.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeWhile {
                    val pBytes = it.toByteArray(Charsets.UTF_8).size
                    if (totalTextBytes + chapterBytes + pBytes > JailQuotas.MAX_TEXT_BYTES) {
                        false
                    } else {
                        chapterBytes += pBytes
                        true
                    }
                }
                
                totalTextBytes += chapterBytes
                totalChapters++

                if ((chapTitle.startsWith("Chapter", ignoreCase = true) || chapTitle.startsWith("Page", ignoreCase = true) || chapTitle.length < 2) && paragraphs.isNotEmpty()) {
                    val firstLine = paragraphs.first()
                    if (firstLine.length in 3..100) chapTitle = firstLine
                }
                if (paragraphs.isNotEmpty()) chapters.add(chapTitle to paragraphs)
            }
        } catch (e: Exception) {} finally {
            try { zip?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
        return chapters
    }

    private fun decodeHtmlEntities(text: String): String {
        var decoded = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&mdash;", "-")
            .replace("&ndash;", "-")
            .replace("&hellip;", ".")
            .replace("&lsquo;", "")
            .replace("&rsquo;", "'")
            .replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")
            .replace("&bull;", " ")
        
        decoded = Regex("""&#x([0-9a-fA-F]+);""").replace(decoded) {
            try { it.groupValues[1].toInt(16).toChar().toString() } catch (e: Exception) { it.value }
        }
        decoded = Regex("""&#([0-9]+);""").replace(decoded) {
            try { it.groupValues[1].toInt().toChar().toString() } catch (e: Exception) { it.value }
        }
        return decoded
    }

    private fun readZipEntrySafely(zipFile: ZipFile, entry: ZipEntry, maxChars: Int): String {
        return zipFile.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            val sb = java.lang.StringBuilder()
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                sb.append(buffer, 0, read)
                if (sb.length > maxChars) break
            }
            sb.toString()
        }
    }
}
