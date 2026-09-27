package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
object PdfToEpubEngine {

    /**
     * Converts a PDF into a standard, fully reflowable EPUB 3.0 e-book archive.
     */
    suspend fun pdfToEpub(
        context: Context,
        inputStream: InputStream,
        outputStream: java.io.OutputStream,
        bookTitle: String = "Untitled E-Book",
        authorName: String = "Unknown Author"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        var document: PDDocument? = null
        var tempEpubFile: File? = null

        try {
            val memSettings = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(10 * 1024 * 1024, 250 * 1024 * 1024)
            document = PDDocument.load(inputStream, memSettings)
            val pageCount = document.numberOfPages
            if (pageCount == 0) {
                return@withContext Result.failure(IllegalStateException("PDF contains no pages"))
            }

            val pageTexts = PdfTextExtractor.extractAllPagesText(document)
            val chapters = if (pageTexts.isNotEmpty()) {
                pageTexts.mapIndexed { index, text ->
                    val trimmed = text.trim()
                    if (trimmed.isNotBlank()) trimmed else "Page ${index + 1}"
                }
            } else {
                (1..pageCount).map { "Page $it" }
            }

            document.close()
            document = null

            // Build EPUB package in temp file
            val tempFile = File(context.cacheDir, "ebook_${System.currentTimeMillis()}.epub")
            tempEpubFile = tempFile
            val bookId = "urn:uuid:" + UUID.randomUUID().toString()

            ZipOutputStream(FileOutputStream(tempFile)).use { zip ->
                // 1. mimetype (must be uncompressed/stored)
                val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                val mimeEntry = ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimeBytes.size.toLong()
                    compressedSize = mimeBytes.size.toLong()
                    val crc = CRC32()
                    crc.update(mimeBytes)
                    setCrc(crc.value)
                }
                zip.putNextEntry(mimeEntry)
                zip.write(mimeBytes)
                zip.closeEntry()

                // 2. META-INF/container.xml
                val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write(containerXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 3. OEBPS/stylesheet.css
                val css = """body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Georgia, serif;
  line-height: 1.6;
  margin: 5% 8%;
  color: #1a1a1a;
  background-color: #ffffff;
}
h1, h2, h3 { color: #111; margin-top: 1.5em; }
p { margin-bottom: 1.2em; text-align: justify; }
@media (prefers-color-scheme: dark) {
  body { color: #e0e0e0; background-color: #121212; }
  h1, h2, h3 { color: #ffffff; }
}"""
                zip.putNextEntry(ZipEntry("OEBPS/stylesheet.css"))
                zip.write(css.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 4. OEBPS/chapter_*.xhtml files
                val chapterFiles = mutableListOf<String>()
                for ((idx, chapterText) in chapters.withIndex()) {
                    val chapNum = idx + 1
                    val chapFileName = "chapter_$chapNum.xhtml"
                    chapterFiles.add(chapFileName)

                    val paragraphsHtml = chapterText.lines()
                        .filter { it.isNotBlank() }
                        .joinToString("\n") { "<p>${escapeHtml(it.trim())}</p>" }

                    val xhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en">
<head>
  <title>Chapter $chapNum</title>
  <link rel="stylesheet" type="text/css" href="stylesheet.css"/>
</head>
<body>
  <h2>Page $chapNum</h2>
  $paragraphsHtml
</body>
</html>"""
                    zip.putNextEntry(ZipEntry("OEBPS/$chapFileName"))
                    zip.write(xhtml.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                // 5. OEBPS/nav.xhtml (EPUB 3 Navigation)
                val navLi = chapterFiles.mapIndexed { idx, fn ->
                    """<li><a href="$fn">Page ${idx + 1}</a></li>"""
                }.joinToString("\n")

                val navXhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en">
<head><title>Table of Contents</title><link rel="stylesheet" type="text/css" href="stylesheet.css"/></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Table of Contents</h1>
    <ol>
      $navLi
    </ol>
  </nav>
</body>
</html>"""
                zip.putNextEntry(ZipEntry("OEBPS/nav.xhtml"))
                zip.write(navXhtml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 5b. OEBPS/toc.ncx (EPUB 2 backward compatibility)
                val ncxNavPoints = chapterFiles.mapIndexed { idx, fn ->
                    """    <navPoint id="np_${idx + 1}" playOrder="${idx + 1}">
      <navLabel><text>Page ${idx + 1}</text></navLabel>
      <content src="$fn"/>
    </navPoint>"""
                }.joinToString("\n")

                val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$bookId"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${escapeHtml(bookTitle)}</text></docTitle>
  <docAuthor><text>${escapeHtml(authorName)}</text></docAuthor>
  <navMap>
$ncxNavPoints
  </navMap>
</ncx>"""
                zip.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
                zip.write(ncx.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // 6. OEBPS/content.opf
                val manifestItems = chapterFiles.mapIndexed { idx, fn ->
                    """<item id="chap_${idx + 1}" href="$fn" media-type="application/xhtml+xml"/>"""
                }.joinToString("\n    ")

                val spineItems = chapterFiles.mapIndexed { idx, _ ->
                    """<itemref idref="chap_${idx + 1}"/>"""
                }.joinToString("\n    ")

                val opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="BookId">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="BookId">$bookId</dc:identifier>
    <dc:title>${escapeHtml(bookTitle)}</dc:title>
    <dc:creator>${escapeHtml(authorName)}</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-08-25T12:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="stylesheet.css" media-type="text/css"/>
    $manifestItems
  </manifest>
  <spine toc="ncx">
    $spineItems
  </spine>
</package>"""
                zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zip.write(opf.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            // Stream to destUri
            tempEpubFile.inputStream().use { inStream ->
                inStream.copyTo(outputStream)
            }

            tempEpubFile?.delete()
            tempEpubFile = null

            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfToEpubEngine: Error converting PDF to EPUB", e)
            Result.failure(e)
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            tempEpubFile?.delete()
        }
    }

    /**
     * Converts an EPUB e-book into an A4 PDF document with styled typography and pagination.
     */
    suspend fun epubToPdf(
        context: Context,
        inputStream: InputStream,
        outputStream: java.io.OutputStream,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        var tempFile: File? = null
        var zip: java.util.zip.ZipFile? = null
        val document = PDDocument()
        try {
            tempFile = File(context.cacheDir, "epub_in_${System.currentTimeMillis()}.epub")
            FileOutputStream(tempFile).use { output -> inputStream.copyTo(output) }

            val openZip = java.util.zip.ZipFile(tempFile)
            zip = openZip

            // 1. Locate OPF from META-INF/container.xml
            var opfPath = "OEBPS/content.opf"
            val containerEntry = zip.getEntry("META-INF/container.xml")
            if (containerEntry != null) {
                val containerText = readZipEntrySafely(zip, containerEntry)
                val rootfileRegex = Regex("""full-path\s*=\s*["']([^"']+)["']""")
                val match = rootfileRegex.find(containerText)
                if (match != null) {
                    opfPath = match.groupValues[1]
                }
            }

            // 2. Read OPF
            val opfEntry = zip.getEntry(opfPath) ?: throw IllegalStateException("EPUB metadata (content.opf) not found")
            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""
            val opfText = readZipEntrySafely(zip, opfEntry)

            // Extract Title
            val titleMatch = Regex("""<dc:title[^>]*>([^<]+)</dc:title>""", RegexOption.IGNORE_CASE).find(opfText)
            val bookTitle = titleMatch?.groupValues?.get(1)?.trim() ?: "Converted E-Book"

            // Map manifest items
            val itemMap = mutableMapOf<String, String>()
            val itemRegex = Regex("""<item\s+[^>]*id\s*=\s*["']([^"']+)["'][^>]*href\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
            for (m in itemRegex.findAll(opfText)) {
                val id = m.groupValues[1]
                val href = m.groupValues[2]
                itemMap[id] = opfDir + href
            }
            val itemRegex2 = Regex("""<item\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*id\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
            for (m in itemRegex2.findAll(opfText)) {
                val href = m.groupValues[1]
                val id = m.groupValues[2]
                itemMap[id] = opfDir + href
            }

            // Extract Spine order
            val spineItems = mutableListOf<String>()
            val itemrefRegex = Regex("""<itemref\s+[^>]*idref\s*=\s*["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
            for (m in itemrefRegex.findAll(opfText)) {
                val idref = m.groupValues[1]
                val resolvedPath = itemMap[idref]
                if (resolvedPath != null && (resolvedPath.endsWith(".xhtml", true) || resolvedPath.endsWith(".html", true) || resolvedPath.endsWith(".htm", true))) {
                    if (!resolvedPath.contains("nav.xhtml", true)) {
                        spineItems.add(resolvedPath)
                    }
                }
            }

            if (spineItems.isEmpty()) {
                val allHtml = zip.entries().toList().filter { 
                    it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) 
                }.filter { !it.name.contains("nav.xhtml", true) }.sortedBy { it.name }
                for (entry in allHtml) {
                    spineItems.add(entry.name)
                }
            }

            if (spineItems.isEmpty()) {
                throw IllegalStateException("No readable content chapters found in EPUB archive")
            }

            // 3. Extract text paragraphs
            val allParagraphs = mutableListOf<String>()
            val totalChapters = spineItems.size

            for ((chapIdx, path) in spineItems.withIndex()) {
                onProgress(chapIdx + 1, totalChapters)
                val entry = zip.getEntry(path) ?: continue
                val html = readZipEntrySafely(zip, entry)
                val text = cleanHtmlToPlainText(html)
                if (text.isNotBlank()) {
                    allParagraphs.addAll(text.split("\n\n").filter { it.isNotBlank() })
                }
            }
            zip.close()

            // 4. Render paragraphs into PDFBox document
            val pageWidth = 595f
            val pageHeight = 842f
            val margin = 50f
            val maxLineWidth = pageWidth - (margin * 2)
            val font = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
            val titleFont = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD
            val fontSize = 11f
            val leading = 15f

            var currentPage = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
            document.addPage(currentPage)
            var contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, currentPage)
            var currentY = pageHeight - margin

            // Draw Book Title on first page
            contentStream.beginText()
            contentStream.setFont(titleFont, 18f)
            contentStream.newLineAtOffset(margin, currentY)
            contentStream.showText(sanitizeText(bookTitle.take(60)))
            contentStream.endText()
            currentY -= 35f

            for (para in allParagraphs) {
                val lines = wordWrap(sanitizeText(para), font, fontSize, maxLineWidth)
                for (line in lines) {
                    if (currentY < margin + leading) {
                        contentStream.close()
                        currentPage = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
                        document.addPage(currentPage)
                        contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, currentPage)
                        currentY = pageHeight - margin
                    }

                    contentStream.beginText()
                    contentStream.setFont(font, fontSize)
                    contentStream.newLineAtOffset(margin, currentY)
                    contentStream.showText(line)
                    contentStream.endText()
                    currentY -= leading
                }
                currentY -= 8f
            }
            contentStream.close()

            // Save PDF to destination
            document.save(outputStream)

            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfToEpubEngine: Error converting EPUB to PDF", e)
            Result.failure(e)
        } finally {
            try { zip?.close() } catch (_: Throwable) {}
            try { document.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    private fun readZipEntrySafely(zip: java.util.zip.ZipFile, entry: java.util.zip.ZipEntry, maxChars: Int = 2_000_000): String {
        return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            val sb = java.lang.StringBuilder()
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                sb.append(buffer, 0, read)
                if (sb.length > maxChars) {
                    AppLogger.w("PdfToEpubEngine: Entry ${entry.name} exceeded size limit ($maxChars chars)")
                    break
                }
            }
            sb.toString()
        }
    }

    private fun cleanHtmlToPlainText(html: String): String {
        var s = html.replace(Regex("""<head.*?</head>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        s = s.replace(Regex("""<style.*?</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        s = s.replace(Regex("""<script.*?</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        s = s.replace(Regex("""<(p|h[1-6]|div|li)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<[^>]+>"""), "")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
        return s.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    private fun sanitizeText(text: String): String {
        return text.replace("“", "\"")
            .replace("”", "\"")
            .replace("‘", "'")
            .replace("’", "'")
            .replace("—", "--")
            .replace("–", "-")
            .replace("…", "...")
            .filter { it.code in 32..126 || it.code in 160..255 }
    }

    private fun wordWrap(text: String, font: com.tom_roush.pdfbox.pdmodel.font.PDFont, fontSize: Float, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        val words = text.split(Regex("\\s+"))
        var currentLine = StringBuilder()

        for (word in words) {
            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
            val width = try {
                font.getStringWidth(candidate) / 1000 * fontSize
            } catch (_: Exception) {
                maxWidth + 1f
            }
            if (width > maxWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            } else {
                currentLine = StringBuilder(candidate)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

