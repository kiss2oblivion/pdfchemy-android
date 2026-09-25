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
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfToEpubEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_for_epub.pdf")
        val doc = PDDocument()
        val page1 = PDPage()
        val page2 = PDPage()
        doc.addPage(page1)
        doc.addPage(page2)

        PDPageContentStream(doc, page1).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Chapter 1: The Beginning of Digital Publishing.")
            cs.endText()
        }

        PDPageContentStream(doc, page2).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Chapter 2: The Future of E-Books and Reflow.")
            cs.endText()
        }

        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testPdfToEpub_createsCompliantEpubPackage() = runBlocking {
        val destEpubFile = File(context.cacheDir, "output_book.epub")
        val destEpubUri = Uri.fromFile(destEpubFile)

        val result = PdfToEpubEngine.pdfToEpub(
            context = context,
            inputStream = FileInputStream(samplePdfFile),
            outputStream = FileOutputStream(destEpubFile),
            bookTitle = "Sample E-Book",
            authorName = "Author Name"
        )

        assertTrue(result.isSuccess)
        assertTrue(destEpubFile.exists() && destEpubFile.length() > 0)

        // Verify valid EPUB / ZIP container structure
        val zip = ZipFile(destEpubFile)
        assertNotNull("Must contain mimetype", zip.getEntry("mimetype"))
        assertNotNull("Must contain META-INF/container.xml", zip.getEntry("META-INF/container.xml"))
        assertNotNull("Must contain OEBPS/content.opf", zip.getEntry("OEBPS/content.opf"))
        assertNotNull("Must contain OEBPS/nav.xhtml", zip.getEntry("OEBPS/nav.xhtml"))
        assertNotNull("Must contain chapter 1", zip.getEntry("OEBPS/chapter_1.xhtml"))
        assertNotNull("Must contain chapter 2", zip.getEntry("OEBPS/chapter_2.xhtml"))
        zip.close()
    }
}
