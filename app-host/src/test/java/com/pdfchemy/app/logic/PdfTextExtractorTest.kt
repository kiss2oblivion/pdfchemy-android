package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfTextExtractorTest {

    private lateinit var context: Context
    private lateinit var tempPdfFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        // Create a dummy PDF with text
        tempPdfFile = File(context.cacheDir, "test_text_doc.pdf")
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        val font = PDType1Font.HELVETICA
        
        PDPageContentStream(document, page).use { contentStream ->
            contentStream.beginText()
            contentStream.setFont(font, 12f)
            contentStream.newLineAtOffset(100f, 700f)
            contentStream.showText("Hello ShrinkPDF")
            contentStream.endText()
            
            contentStream.beginText()
            contentStream.setFont(font, 12f)
            contentStream.newLineAtOffset(100f, 650f)
            contentStream.showText("This is a test of text extraction.")
            contentStream.endText()
        }

        document.save(tempPdfFile)
        document.close()
    }

    @After
    fun tearDown() {
        if (tempPdfFile.exists()) {
            tempPdfFile.delete()
        }
    }

    @Test
    fun extractText_readsRawTextFromPdf() = runTest {
        // Arrange
        val sourceUri = Uri.fromFile(tempPdfFile)
        val destFile = File(context.cacheDir, "extracted_text.txt")
        val destUri = Uri.fromFile(destFile)

        // Act
        val result = PdfTextExtractor.extractText(context, sourceUri, destUri)

        // Assert
        assertTrue("Extraction should be successful", result)
        assertTrue("Output file should exist", destFile.exists())
        
        val text = destFile.readText()
        assertTrue("Text should contain 'Hello ShrinkPDF'", text.contains("Hello ShrinkPDF"))
        assertTrue("Text should contain 'This is a test of text extraction.'", text.contains("This is a test of text extraction."))
        
        // Clean up
        destFile.delete()
    }
}
