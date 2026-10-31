package com.pdfchemy.app.jail.engines

import android.content.Context
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.logic.OfficeFormat
import com.pdfchemy.app.logic.OfficeExportReport

import com.pdfchemy.app.sandbox.JailNativeRendererCoordinator
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import android.graphics.BitmapFactory
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToLong
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object OfficeExportEngineWorker {

    fun exportToWord(context: Context, sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor): String {
        return try {
            var doc: PDDocument? = null
            var totalParagraphs = 0
            var pageCount = 0
            try {
                FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                    doc = PDDocument.load(inStream, com.pdfchemy.app.jail.JailMemory.settings())
                }
                
                pageCount = doc!!.numberOfPages
                val textByPage = PdfTextExtractorWorker.extractAllPagesText(doc!!)
                
                FileOutputStream(targetFd.fileDescriptor).use { out ->
                    totalParagraphs = writeDocxArchive(out, textByPage)
                }
            } finally {
                try { doc?.close() } catch (_: Exception) {}
            }
            
            val outSize = com.pdfchemy.app.jail.JailScratch.outputSize(targetFd.fileDescriptor)
            val obj = JSONObject()
            obj.put("success", true)
            obj.put("pageCount", pageCount)
            obj.put("outputSizeBytes", outSize)
            obj.put("itemsExtracted", totalParagraphs)
            obj.toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    fun exportToExcel(context: Context, sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor): String {
        return try {
            var doc: PDDocument? = null
            var totalCells = 0
            var pageCount = 0
            try {
                FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                    doc = PDDocument.load(inStream, com.pdfchemy.app.jail.JailMemory.settings())
                }
                
                pageCount = doc!!.numberOfPages
                val allPagesText = PdfTextExtractorWorker.extractAllPagesText(doc!!)
                val pagesRows = allPagesText.map { parsePageToTableRows(it) }
                
                FileOutputStream(targetFd.fileDescriptor).use { out ->
                    totalCells = writeXlsxArchive(out, pagesRows)
                }
            } finally {
                try { doc?.close() } catch (_: Exception) {}
            }
            
            val outSize = com.pdfchemy.app.jail.JailScratch.outputSize(targetFd.fileDescriptor)
            val obj = JSONObject()
            obj.put("success", true)
            obj.put("pageCount", pageCount)
            obj.put("outputSizeBytes", outSize)
            obj.put("itemsExtracted", totalCells)
            obj.toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    private data class PresentationPage(val width: Double, val height: Double)

    fun exportToPowerPoint(context: Context, sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, rendererBinder: android.os.IBinder? = null): String {
        return try {
            checkNotNull(rendererBinder) { "PowerPoint export requires the isolated renderer" }
            val pages = FileInputStream(sourceFd.fileDescriptor).use { input ->
                PDDocument.load(input, com.pdfchemy.app.jail.JailMemory.settings()).use { document ->
                    require(document.numberOfPages in 1..SecurityLimits.MAX_PAGE_COUNT) { "No readable pages or page quota exceeded" }
                    document.pages.map { page ->
                        val box = page.cropBox
                        val rotation = ((page.rotation % 360) + 360) % 360
                        require(rotation in listOf(0, 90, 180, 270)) { "Invalid page rotation" }
                        val width = box.width.toDouble(); val height = box.height.toDouble()
                        require(width.isFinite() && height.isFinite() && width > 0 && height > 0) { "Invalid page dimensions" }
                        if (rotation == 90 || rotation == 270) PresentationPage(height, width)
                        else PresentationPage(width, height)
                    }
                }
            }
            runBlocking {
                val count = JailNativeRendererCoordinator.getPageCount(sourceFd, rendererBinder)
                check(count == pages.size) { "Renderer page count does not match the PDF" }
                FileOutputStream(targetFd.fileDescriptor).use { output ->
                    writePptxArchive(output, pages) { index, page, zip, budget ->
                        streamPresentationImage(sourceFd, index, page, zip, budget, rendererBinder)
                    }
                }
            }
            JSONObject().put("success", true).put("pageCount", pages.size)
                .put("outputSizeBytes", com.pdfchemy.app.jail.JailScratch.outputSize(targetFd.fileDescriptor))
                .put("itemsExtracted", pages.size).toString()
        } catch (error: Exception) {
            JSONObject().put("error", error.message ?: "PowerPoint export failed").toString()
        }
    }

    /** One bounded JPEG pipe at a time; no page-image files accumulate in worker scratch. */
    private suspend fun streamPresentationImage(
        source: ParcelFileDescriptor, index: Int, page: PresentationPage,
        output: OutputStream, budget: Long, renderer: android.os.IBinder
    ): Long = coroutineScope {
        val pipe = ParcelFileDescriptor.createPipe()
        val producer = async(Dispatchers.IO) {
            try {
                check(JailNativeRendererCoordinator.renderPageToJpeg(source, index, pipe[1], renderer) == true) {
                    "Renderer did not complete page ${index + 1}"
                }
            } finally { pipe[1].close() }
        }
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                val cancellation = currentCoroutineContext()
                var bytes = 0L
                var prefix = 0; var prefixBytes = 0; var trailer = 0
                fun record(buffer: ByteArray, offset: Int, count: Int) {
                    cancellation.ensureActive()
                    require(count.toLong() <= budget - bytes) { "Presentation image quota exceeded" }
                    for (n in offset until offset + minOf(count, 2 - prefixBytes)) {
                        prefix = (prefix shl 8) or (buffer[n].toInt() and 255); prefixBytes++
                    }
                    if (count >= 2) trailer = ((buffer[offset + count - 2].toInt() and 255) shl 8) or (buffer[offset + count - 1].toInt() and 255)
                    else if (count == 1) trailer = ((trailer shl 8) or (buffer[offset].toInt() and 255)) and 65535
                    output.write(buffer, offset, count)
                    bytes += count
                }
                // Decode bounds through a tee, then drain the rest. This checks a
                // real JPEG frame without allocating a second raster or buffering
                // the image. Decoder read-ahead is recorded exactly once as well.
                val recording = object : java.io.InputStream() {
                    private val single = ByteArray(1)
                    override fun read(): Int {
                        val value = input.read()
                        if (value >= 0) { single[0] = value.toByte(); record(single, 0, 1) }
                        return value
                    }
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                        val read = input.read(buffer, offset, count)
                        if (read > 0) record(buffer, offset, read)
                        return read
                    }
                    override fun skip(count: Long): Long {
                        val buffer = ByteArray(8192)
                        var skipped = 0L
                        while (skipped < count) {
                            val read = read(buffer, 0, minOf(buffer.size.toLong(), count - skipped).toInt())
                            if (read < 0) break
                            skipped += read
                        }
                        return skipped
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(recording, null, bounds)?.recycle()
                check(bounds.outMimeType == "image/jpeg") { "Renderer did not return a JPEG" }
                SecurityLimits.requirePixels(bounds.outWidth, bounds.outHeight)
                val aspectError = kotlin.math.abs(bounds.outWidth.toDouble() / bounds.outHeight - page.width / page.height)
                check(aspectError <= 2.0 / minOf(bounds.outWidth, bounds.outHeight)) { "Rendered page aspect ratio mismatch" }
                val buffer = ByteArray(64 * 1024)
                while (true) { val read = recording.read(buffer); if (read < 0) break }
                producer.await()
                check(bytes >= 4 && prefix == 0xffd8 && trailer == 0xffd9) { "Incomplete rendered JPEG" }
                bytes
            }
        } finally {
            runCatching { pipe[0].close() }; runCatching { pipe[1].close() }
            producer.cancel()
        }
    }

    // =========================================================================
    // Word (.docx) OpenXML Archive Builder
    // =========================================================================

    private fun writeDocxArchive(outputStream: OutputStream, textByPage: List<String>): Int {
        val zip = ZipOutputStream(outputStream)
        var totalParagraphs = 0

        // 1. [Content_Types].xml
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
    <Default Extension="xml" ContentType="application/xml"/>
    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
    <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""
        zip.write(contentTypesXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 2. _rels/.rels
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
        zip.write(rootRelsXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 3. word/_rels/document.xml.rels
        zip.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
        val docRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
        zip.write(docRelsXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 4. word/styles.xml
        zip.putNextEntry(ZipEntry("word/styles.xml"))
        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
    <w:docDefaults>
        <w:rPrDefault>
            <w:rPr>
                <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/>
                <w:sz w:val="22"/>
                <w:szCs w:val="22"/>
                <w:color w:val="222222"/>
            </w:rPr>
        </w:rPrDefault>
    </w:docDefaults>
</w:styles>"""
        zip.write(stylesXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 5. word/document.xml
        zip.putNextEntry(ZipEntry("word/document.xml"))
        val docSb = StringBuilder()
        docSb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>""")

        for ((pageIdx, pageText) in textByPage.withIndex()) {
            val lines = pageText.lines()
            for (line in lines) {
                val cleanLine = escapeXml(line.trim())
                if (cleanLine.isNotEmpty()) {
                    totalParagraphs++
                    docSb.append("<w:p><w:r><w:t xml:space=\"preserve\">")
                    docSb.append(cleanLine)
                    docSb.append("</w:t></w:r></w:p>")
                }
            }
            if (pageIdx < textByPage.size - 1) {
                // Page break
                docSb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            }
        }

        docSb.append("""<w:sectPr>
    <w:pgSz w:w="12240" w:h="15840"/>
    <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
</w:sectPr>
</w:body></w:document>""")

        zip.write(docSb.toString().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        zip.finish()
        return totalParagraphs
    }

    // =========================================================================
    // Excel (.xlsx) OpenXML Archive Builder
    // =========================================================================

    private fun parsePageToTableRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Split by tab or multiple spaces (>= 2 spaces indicating columns) or commas
            val tokens = if (trimmed.contains("\t")) {
                trimmed.split("\t")
            } else if (trimmed.contains(Regex("\\s{2,}"))) {
                trimmed.split(Regex("\\s{2,}"))
            } else if (trimmed.contains(",") && trimmed.split(",").size >= 3) {
                trimmed.split(",")
            } else {
                listOf(trimmed)
            }
            rows.add(tokens.map { it.trim() })
        }
        return rows
    }

    private fun writeXlsxArchive(outputStream: OutputStream, pagesRows: List<List<List<String>>>): Int {
        val zip = ZipOutputStream(outputStream)
        var totalCells = 0

        // 1. [Content_Types].xml
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
    <Default Extension="xml" ContentType="application/xml"/>
    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
        zip.write(contentTypesXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 2. _rels/.rels
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
        zip.write(rootRelsXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 3. xl/_rels/workbook.xml.rels
        zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
        val wbRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
        zip.write(wbRelsXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 4. xl/styles.xml
        zip.putNextEntry(ZipEntry("xl/styles.xml"))
        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
    <fonts count="1">
        <font><sz val="11"/><name val="Calibri"/></font>
    </fonts>
    <fills count="2">
        <fill><patternFill patternType="none"/></fill>
        <fill><patternFill patternType="gray125"/></fill>
    </fills>
    <borders count="1">
        <border><left/><right/><top/><bottom/></border>
    </borders>
    <cellXfs count="1">
        <xf fontId="0" fillId="0" borderId="0"/>
    </cellXfs>
</styleSheet>"""
        zip.write(stylesXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 5. xl/workbook.xml
        zip.putNextEntry(ZipEntry("xl/workbook.xml"))
        val wbXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
    <sheets>
        <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
    </sheets>
</workbook>"""
        zip.write(wbXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 6. xl/worksheets/sheet1.xml
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        val sheetSb = StringBuilder()
        sheetSb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetData>""")

        var rowIndex = 1
        for (pageRows in pagesRows) {
            for (rowTokens in pageRows) {
                if (rowTokens.isEmpty()) continue
                sheetSb.append("<row r=\"$rowIndex\">")
                for ((colIdx, token) in rowTokens.withIndex()) {
                    val colLetter = getColumnLetter(colIdx)
                    val cellRef = "$colLetter$rowIndex"
                    val cleanText = escapeXml(token)
                    
                    val isNumber = token.toDoubleOrNull() != null
                    if (isNumber) {
                        sheetSb.append("<c r=\"$cellRef\" t=\"n\"><v>$token</v></c>")
                    } else {
                        sheetSb.append("<c r=\"$cellRef\" t=\"inlineStr\"><is><t>$cleanText</t></is></c>")
                    }
                    totalCells++
                }
                sheetSb.append("</row>")
                rowIndex++
            }
            // Blank spacer row between pages
            rowIndex++
        }

        sheetSb.append("""</sheetData></worksheet>""")
        zip.write(sheetSb.toString().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        zip.finish()
        return totalCells
    }

    private fun getColumnLetter(colIndex: Int): String {
        var temp = colIndex
        val sb = StringBuilder()
        while (temp >= 0) {
            sb.insert(0, ('A'.code + (temp % 26)).toChar())
            temp = (temp / 26) - 1
        }
        return sb.toString()
    }

    // =========================================================================
    // PowerPoint (.pptx) OpenXML Archive Builder
    // =========================================================================

    private suspend fun writePptxArchive(
        outputStream: OutputStream,
        pages: List<PresentationPage>,
        image: suspend (Int, PresentationPage, OutputStream, Long) -> Long
    ) {
        val zip = ZipOutputStream(outputStream)
        val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
        val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        fun xml(name: String, body: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + body).toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        fun rel(id: String, type: String, target: String) = """<Relationship Id="$id" Type="$r/$type" Target="$target"/>"""
        fun rels(name: String, body: String) = xml(name, """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$body</Relationships>""")
        val group = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"""
        val types = buildString {
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/>""")
            for ((name, type) in listOf("presentation.xml" to "presentation.main", "slideMasters/slideMaster1.xml" to "slideMaster", "slideLayouts/slideLayout1.xml" to "slideLayout")) {
                append("""<Override PartName="/ppt/$name" ContentType="application/vnd.openxmlformats-officedocument.presentationml.$type+xml"/>""")
            }
            append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
            for (i in pages.indices) append("""<Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
            append("</Types>")
        }
        xml("[Content_Types].xml", types)
        rels("_rels/.rels", rel("rId1", "officeDocument", "ppt/presentation.xml"))
        rels("ppt/_rels/presentation.xml.rels", rel("rIdMaster", "slideMaster", "slideMasters/slideMaster1.xml") +
            pages.indices.joinToString("") { rel("rId${it + 1}", "slide", "slides/slide${it + 1}.xml") })
        val slideIds = pages.indices.joinToString("") { """<p:sldId id="${256 + it}" r:id="rId${it + 1}"/>""" }
        val slideWidth = 12192000L; val slideHeight = 6858000L
        xml("ppt/presentation.xml", """<p:presentation xmlns:a="$a" xmlns:r="$r" xmlns:p="$p"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rIdMaster"/></p:sldMasterIdLst><p:sldIdLst>$slideIds</p:sldIdLst><p:sldSz cx="$slideWidth" cy="$slideHeight"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""")
        xml("ppt/slideMasters/slideMaster1.xml", """<p:sldMaster xmlns:a="$a" xmlns:r="$r" xmlns:p="$p"><p:cSld name="PDF pages"><p:spTree>$group</p:spTree></p:cSld><p:clrMap accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" bg1="lt1" bg2="lt2" folHlink="folHlink" hlink="hlink" tx1="dk1" tx2="dk2"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rIdLayout"/></p:sldLayoutIdLst></p:sldMaster>""")
        rels("ppt/slideMasters/_rels/slideMaster1.xml.rels", rel("rIdLayout", "slideLayout", "../slideLayouts/slideLayout1.xml") + rel("rIdTheme", "theme", "../theme/theme1.xml"))
        xml("ppt/slideLayouts/slideLayout1.xml", """<p:sldLayout xmlns:a="$a" xmlns:r="$r" xmlns:p="$p" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>$group</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>""")
        rels("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rel("rIdMaster", "slideMaster", "../slideMasters/slideMaster1.xml"))
        val colors = listOf("dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "222222", "lt2" to "EEEEEE", "accent1" to "4472C4", "accent2" to "ED7D31", "accent3" to "A5A5A5", "accent4" to "FFC000", "accent5" to "5B9BD5", "accent6" to "70AD47", "hlink" to "0000FF", "folHlink" to "800080")
            .joinToString("") { (name, color) -> """<a:$name><a:srgbClr val="$color"/></a:$name>""" }
        val solid = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
        val fonts = """<a:latin typeface="Arial"/><a:ea typeface=""/><a:cs typeface=""/>"""
        xml("ppt/theme/theme1.xml", """<a:theme xmlns:a="$a" name="PDF pages"><a:themeElements><a:clrScheme name="PDF pages">$colors</a:clrScheme><a:fontScheme name="PDF pages"><a:majorFont>$fonts</a:majorFont><a:minorFont>$fonts</a:minorFont></a:fontScheme><a:fmtScheme name="PDF pages"><a:fillStyleLst>${solid.repeat(3)}</a:fillStyleLst><a:lnStyleLst>${("<a:ln w=\"9525\">" + solid + "<a:prstDash val=\"solid\"/></a:ln>").repeat(3)}</a:lnStyleLst><a:effectStyleLst>${"<a:effectStyle><a:effectLst/></a:effectStyle>".repeat(3)}</a:effectStyleLst><a:bgFillStyleLst>${solid.repeat(3)}</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>""")
        var imageBytes = 0L
        for ((index, page) in pages.withIndex()) {
            currentCoroutineContext().ensureActive()
            val number = index + 1
            zip.putNextEntry(ZipEntry("ppt/media/image$number.jpeg"))
            imageBytes += image(index, page, zip, SecurityLimits.MAX_OUTPUT_BYTES - imageBytes)
            zip.closeEntry()
            rels("ppt/slides/_rels/slide$number.xml.rels", rel("rIdImage", "image", "../media/image$number.jpeg") + rel("rIdLayout", "slideLayout", "../slideLayouts/slideLayout1.xml"))
            val scale = minOf(slideWidth / page.width, slideHeight / page.height)
            val width = (page.width * scale).roundToLong().coerceIn(1L, slideWidth)
            val height = (page.height * scale).roundToLong().coerceIn(1L, slideHeight)
            val x = (slideWidth - width) / 2; val y = (slideHeight - height) / 2
            xml("ppt/slides/slide$number.xml", """<p:sld xmlns:a="$a" xmlns:r="$r" xmlns:p="$p"><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></p:bgPr></p:bg><p:spTree>$group<p:pic><p:nvPicPr><p:cNvPr id="2" name="PDF page $number"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdImage"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$width" cy="$height"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>""")
        }
        zip.finish()
    }

    private val INVALID_XML_CONTROL_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")

    private fun escapeXml(text: String): String {
        val sanitized = INVALID_XML_CONTROL_CHARS.replace(text, "")
        return sanitized
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
