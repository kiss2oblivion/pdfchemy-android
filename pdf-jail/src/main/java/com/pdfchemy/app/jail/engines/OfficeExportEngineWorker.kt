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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
                    doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                }
                
                pageCount = doc!!.numberOfPages
                val textByPage = PdfTextExtractorWorker.extractAllPagesText(doc!!)
                
                FileOutputStream(targetFd.fileDescriptor).use { out ->
                    totalParagraphs = writeDocxArchive(out, textByPage)
                }
            } finally {
                try { doc?.close() } catch (_: Exception) {}
            }
            
            val outSize = targetFd.statSize
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
                    doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
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
            
            val outSize = targetFd.statSize
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

    fun exportToPowerPoint(context: Context, sourceFd: ParcelFileDescriptor, targetFd: ParcelFileDescriptor, rendererBinder: android.os.IBinder? = null): String {
        return try {
            var doc: PDDocument? = null
            var pageCount = 0
            var slideTexts: List<List<String>> = emptyList()
            val slideImages = mutableListOf<File>()
            
            try {
                FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                    doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                }
                
                pageCount = doc!!.numberOfPages
                val allPagesText = PdfTextExtractorWorker.extractAllPagesText(doc!!)
                slideTexts = allPagesText.map { pageText ->
                    pageText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                }

                // Render slide images via IPC to PdfNativeRendererService
                runBlocking {
                    val renderedPageCount = JailNativeRendererCoordinator.getPageCount(sourceFd, rendererBinder)
                    if (renderedPageCount != null) {
                        for (i in 0 until renderedPageCount) {
                            val tempImgFile = File(context.cacheDir, "slide_${System.currentTimeMillis()}_$i.jpg")
                            val pipe = ParcelFileDescriptor.createPipe()
                            val readFd = pipe[0]
                            val writeFd = pipe[1]
                            
                            val renderJob = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) {
                                try {
                                    JailNativeRendererCoordinator.renderPageToJpeg(sourceFd, i, writeFd, rendererBinder)
                                } finally {
                                    try { writeFd.close() } catch (e: Exception) {}
                                }
                            }
                            
                            try {
                                ParcelFileDescriptor.AutoCloseInputStream(readFd).use { pipeIn ->
                                    FileOutputStream(tempImgFile).use { fos ->
                                        pipeIn.copyTo(fos)
                                    }
                                }
                            } finally {
                                renderJob.await()
                            }
                            slideImages.add(tempImgFile)
                        }
                    }
                }

                FileOutputStream(targetFd.fileDescriptor).use { out ->
                    writePptxArchive(out, slideTexts, slideImages)
                }

            } finally {
                try { doc?.close() } catch (_: Exception) {}
                slideImages.forEach { it.delete() }
            }
            
            val outSize = targetFd.statSize
            val obj = JSONObject()
            obj.put("success", true)
            obj.put("pageCount", pageCount)
            obj.put("outputSizeBytes", outSize)
            obj.put("itemsExtracted", slideTexts.size)
            obj.toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
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
                sheetSb.append("<row r=\"${"$"}rowIndex\">")
                for ((colIdx, token) in rowTokens.withIndex()) {
                    val colLetter = getColumnLetter(colIdx)
                    val cellRef = "${"$"}colLetter${"$"}rowIndex"
                    val cleanText = escapeXml(token)
                    
                    val isNumber = token.toDoubleOrNull() != null
                    if (isNumber) {
                        sheetSb.append("<c r=\"${"$"}cellRef\" t=\"n\"><v>${"$"}token</v></c>")
                    } else {
                        sheetSb.append("<c r=\"${"$"}cellRef\" t=\"inlineStr\"><is><t>${"$"}cleanText</t></is></c>")
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

    private fun writePptxArchive(
        outputStream: OutputStream,
        slideTexts: List<List<String>>,
        slideImages: List<File>
    ) {
        val zip = ZipOutputStream(outputStream)
        val slideCount = maxOf(1, slideTexts.size)

        // 1. [Content_Types].xml
        zip.putNextEntry(ZipEntry("[Content_Types].xml"))
        val ctSb = StringBuilder()
        ctSb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
    <Default Extension="xml" ContentType="application/xml"/>
    <Default Extension="jpeg" ContentType="image/jpeg"/>
    <Default Extension="jpg" ContentType="image/jpeg"/>
    <Default Extension="png" ContentType="image/png"/>
    <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        
        for (i in 1..slideCount) {
            ctSb.append("<Override PartName=\"/ppt/slides/slide${"$"}i.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        }
        ctSb.append("</Types>")
        zip.write(ctSb.toString().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 2. _rels/.rels
        zip.putNextEntry(ZipEntry("_rels/.rels"))
        val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""
        zip.write(rootRelsXml.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 3. ppt/_rels/presentation.xml.rels
        zip.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
        val presRelsSb = StringBuilder()
        presRelsSb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..slideCount) {
            presRelsSb.append("<Relationship Id=\"rId${"$"}i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${"$"}i.xml\"/>")
        }
        presRelsSb.append("</Relationships>")
        zip.write(presRelsSb.toString().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 4. ppt/presentation.xml
        zip.putNextEntry(ZipEntry("ppt/presentation.xml"))
        val presSb = StringBuilder()
        presSb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
    <p:sldIdLst>""")
        for (i in 1..slideCount) {
            val id = 255 + i
            presSb.append("<p:sldId id=\"${"$"}id\" r:id=\"rId${"$"}i\"/>")
        }
        presSb.append("""</p:sldIdLst>
    <p:sldSz cx="12192000" cy="6858000"/>
    <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>""")
        zip.write(presSb.toString().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        // 5. Individual Slides: ppt/slides/slideN.xml
        for (i in 1..slideCount) {
            val lines = if (i - 1 < slideTexts.size) slideTexts[i - 1] else emptyList()
            val titleText = if (lines.isNotEmpty()) escapeXml(lines[0]) else "Slide ${"$"}i"
            val bodyLines = if (lines.size > 1) lines.drop(1).take(8) else emptyList()

            // Save slide image backdrop if present
            val hasImage = i - 1 < slideImages.size
            if (hasImage) {
                val imgFile = slideImages[i - 1]
                if (imgFile.exists()) {
                    zip.putNextEntry(ZipEntry("ppt/media/image${"$"}i.jpeg"))
                    imgFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                // Relationship for slide image
                zip.putNextEntry(ZipEntry("ppt/slides/_rels/slide${"$"}i.xml.rels"))
                val slideRel = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${"$"}i.jpeg"/>
</Relationships>"""
                zip.write(slideRel.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("ppt/slides/slide${"$"}i.xml"))
            val slideSb = StringBuilder()
            slideSb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
    <p:cSld>
        <p:spTree>
            <p:nvGrpSpPr>
                <p:cNvPr id="1" name=""/>
                <p:cNvGrpSpPr/>
                <p:nvPr/>
            </p:nvGrpSpPr>
            <p:grpSpPr/>""")

            // 1. Title shape
            slideSb.append("""
            <p:sp>
                <p:nvSpPr>
                    <p:cNvPr id="2" name="Title ${"$"}i"/>
                    <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                    <p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                    <a:xfrm><a:off x="800000" y="500000"/><a:ext cx="10592000" cy="1000000"/></a:xfrm>
                </p:spPr>
                <p:txBody>
                    <a:bodyPr/>
                    <a:lstStyle/>
                    <a:p>
                        <a:pPr algn="l"/>
                        <a:r>
                            <a:rPr lang="en-US" sz="2800" b="1"><a:solidFill><a:srgbClr val="1A237E"/></a:solidFill></a:rPr>
                            <a:t>${"$"}titleText</a:t>
                        </a:r>
                    </a:p>
                </p:txBody>
            </p:sp>""")

            // 2. Body shape with bullets
            slideSb.append("""
            <p:sp>
                <p:nvSpPr>
                    <p:cNvPr id="3" name="Content ${"$"}i"/>
                    <p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>
                    <p:nvPr/>
                </p:nvSpPr>
                <p:spPr>
                    <a:xfrm><a:off x="800000" y="1700000"/><a:ext cx="10592000" cy="4500000"/></a:xfrm>
                </p:spPr>
                <p:txBody>
                    <a:bodyPr/>
                    <a:lstStyle/>""")

            if (bodyLines.isNotEmpty()) {
                for (bodyLine in bodyLines) {
                    val cleanBody = escapeXml(bodyLine)
                    slideSb.append("""
                    <a:p>
                        <a:pPr lvl="0"/>
                        <a:r>
                            <a:rPr lang="en-US" sz="1800"><a:solidFill><a:srgbClr val="333333"/></a:solidFill></a:rPr>
                            <a:t>${"$"}cleanBody</a:t>
                        </a:r>
                    </a:p>""")
                }
            } else {
                slideSb.append("""
                    <a:p>
                        <a:r>
                            <a:rPr lang="en-US" sz="1600" i="1"><a:solidFill><a:srgbClr val="888888"/></a:solidFill></a:rPr>
                            <a:t>Content from PDF Page ${"$"}i</a:t>
                        </a:r>
                    </a:p>""")
            }

            slideSb.append("""
                </p:txBody>
            </p:sp>
        </p:spTree>
    </p:cSld>
</p:sld>""")

            zip.write(slideSb.toString().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
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
