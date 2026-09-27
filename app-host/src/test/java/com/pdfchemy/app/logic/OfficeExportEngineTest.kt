package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class OfficeExportEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        // Create a real multi-page PDF document
        samplePdfFile = File(context.cacheDir, "sample_report.pdf")
        val doc = PDDocument()

        // Page 1
        val page1 = PDPage()
        doc.addPage(page1)
        PDPageContentStream(doc, page1).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Quarterly Financial Report")
            cs.endText()

            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Revenue, Expenses, Profit")
            cs.newLineAtOffset(0f, -20f)
            cs.showText("100000, 45000, 55000")
            cs.endText()
        }

        // Page 2
        val page2 = PDPage()
        doc.addPage(page2)
        PDPageContentStream(doc, page2).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Key Conclusions")
            cs.endText()

            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("All targets were achieved successfully.")
            cs.endText()
        }

        FileOutputStream(samplePdfFile).use { out ->
            doc.save(out)
        }
        doc.close()

        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testExportToWordDocx_generatesValidOpenXmlZip() = runBlocking {
        val destDocx = File(context.cacheDir, "output.docx")
        val destUri = Uri.fromFile(destDocx)

        val result = OfficeExportEngine.exportToWord(context, samplePdfUri, destUri)
        assertTrue("Export to Word should succeed", result.isSuccess)

        val report = result.getOrThrow()
        assertEquals(OfficeFormat.WORD, report.format)
        assertEquals(2, report.pageCount)
        assertTrue("Output docx should have size > 0", destDocx.length() > 0)

        // Verify ZIP archive entries
        val entries = mutableListOf<String>()
        ZipInputStream(FileInputStream(destDocx)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                entry = zip.nextEntry
            }
        }

        assertTrue("Must contain [Content_Types].xml", entries.contains("[Content_Types].xml"))
        assertTrue("Must contain word/document.xml", entries.contains("word/document.xml"))
        assertTrue("Must contain word/styles.xml", entries.contains("word/styles.xml"))
    }

    @Test
    fun testExportToExcelXlsx_generatesValidSpreadsheetZip() = runBlocking {
        val destXlsx = File(context.cacheDir, "output.xlsx")
        val destUri = Uri.fromFile(destXlsx)

        val result = OfficeExportEngine.exportToExcel(context, samplePdfUri, destUri)
        assertTrue("Export to Excel should succeed", result.isSuccess)

        val report = result.getOrThrow()
        assertEquals(OfficeFormat.EXCEL, report.format)
        assertEquals(2, report.pageCount)
        assertTrue("Output xlsx should have size > 0", destXlsx.length() > 0)

        // Verify ZIP archive entries
        val entries = mutableListOf<String>()
        ZipInputStream(FileInputStream(destXlsx)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                entry = zip.nextEntry
            }
        }

        assertTrue("Must contain [Content_Types].xml", entries.contains("[Content_Types].xml"))
        assertTrue("Must contain xl/workbook.xml", entries.contains("xl/workbook.xml"))
        assertTrue("Must contain xl/worksheets/sheet1.xml", entries.contains("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun testExportToPowerPointPptx_generatesValidPresentationZip() = runBlocking {
        val destPptx = File(context.cacheDir, "output.pptx")
        val destUri = Uri.fromFile(destPptx)

        val result = OfficeExportEngine.exportToPowerPoint(context, samplePdfUri, destUri)
        assertTrue("Export to PowerPoint should succeed", result.isSuccess)

        val report = result.getOrThrow()
        assertEquals(OfficeFormat.POWERPOINT, report.format)
        assertEquals(2, report.pageCount)
        assertTrue("Output pptx should have size > 0", destPptx.length() > 0)

        // Verify ZIP archive entries
        val entries = mutableListOf<String>()
        ZipInputStream(FileInputStream(destPptx)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                entry = zip.nextEntry
            }
        }

        assertTrue("Must contain [Content_Types].xml", entries.contains("[Content_Types].xml"))
        assertTrue("Must contain ppt/presentation.xml", entries.contains("ppt/presentation.xml"))
        assertTrue("Must contain ppt/slides/slide1.xml", entries.contains("ppt/slides/slide1.xml"))
        assertTrue("Must contain ppt/slides/slide2.xml", entries.contains("ppt/slides/slide2.xml"))
    }
}
