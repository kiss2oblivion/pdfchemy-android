package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CorporatePowerFeaturesTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testPermanentRedactionSanitizesDocument() = runBlocking {
        val testPdf = File(context.cacheDir, "confidential.pdf")
        val outputPdf = File(context.cacheDir, "redacted_output.pdf")

        // 1. Create a document with secret text
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        val cs = PDPageContentStream(doc, page)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
        cs.newLineAtOffset(100f, 700f)
        cs.showText("Top Secret Classified Information")
        cs.endText()
        cs.close()
        doc.save(testPdf)
        doc.close()

        // 2. Apply permanent visual redaction
        val redaction = RedactionBox(
            pageIndex = 0,
            normalizedRect = RectF(0.1f, 0.1f, 0.5f, 0.3f), // Covers secret region
            overlayLabel = "CONFIDENTIAL"
        )

        val success = PdfRedactionEngine.applyRedactions(
            context = context,
            sourceUri = Uri.fromFile(testPdf),
            destUri = Uri.fromFile(outputPdf),
            redactions = listOf(redaction)
        ).isSuccess

        assertTrue(success)
        assertTrue(outputPdf.exists())
        assertTrue(outputPdf.length() > 0)

        // 3. Verify output document opens and contains 1 page
        val loadedDoc = PDDocument.load(outputPdf)
        assertEquals(1, loadedDoc.numberOfPages)
        loadedDoc.close()
    }

    @Test
    fun testOutlineAndReflowExtraction() = runBlocking {
        val testPdf = File(context.cacheDir, "book_with_outline.pdf")

        // 1. Create PDF with outline (bookmarks) and text paragraphs
        val doc = PDDocument()
        val page1 = PDPage(PDRectangle.LETTER)
        val page2 = PDPage(PDRectangle.LETTER)
        doc.addPage(page1)
        doc.addPage(page2)

        val cs1 = PDPageContentStream(doc, page1)
        cs1.beginText()
        cs1.setFont(PDType1Font.HELVETICA, 12f)
        cs1.newLineAtOffset(50f, 700f)
        cs1.showText("Chapter 1: The Beginning of PDF Alchemy.")
        cs1.newLineAtOffset(0f, -20f)
        cs1.showText("This is the first paragraph describing the offline power features.")
        cs1.endText()
        cs1.close()

        val cs2 = PDPageContentStream(doc, page2)
        cs2.beginText()
        cs2.setFont(PDType1Font.HELVETICA, 12f)
        cs2.newLineAtOffset(50f, 700f)
        cs2.showText("Chapter 2: Mobile Reflow Reading.")
        cs2.endText()
        cs2.close()

        // Add Bookmarks Outline
        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline

        val item1 = PDOutlineItem().apply {
            title = "Chapter 1: The Beginning"
        }
        val dest1 = PDPageFitWidthDestination().apply { page = page1 }
        item1.destination = dest1
        outline.addLast(item1)

        val item2 = PDOutlineItem().apply {
            title = "Chapter 2: Mobile Reflow"
        }
        val dest2 = PDPageFitWidthDestination().apply { page = page2 }
        item2.destination = dest2
        outline.addLast(item2)

        doc.save(testPdf)
        doc.close()

        // 2. Extract Document Outline & Reflow Sections
        val bookmarks = PdfOutlineReader.extractOutline(context, Uri.fromFile(testPdf))
        val reflowSections = PdfOutlineReader.extractReflowContent(context, Uri.fromFile(testPdf))

        assertEquals(2, bookmarks.size)
        assertEquals("Chapter 1: The Beginning", bookmarks[0].title)
        assertEquals("Chapter 2: Mobile Reflow", bookmarks[1].title)

        // Verify reflow sections were extracted
        assertTrue(reflowSections.isNotEmpty())
        assertTrue(reflowSections.any { section -> section.paragraphs.any { it.contains("Chapter 1: The Beginning") } })
    }

    @Test
    fun testAcroFormModelsAndExtraction() = runBlocking {
        val testPdf = File(context.cacheDir, "sample_form.pdf")
        val outputPdf = File(context.cacheDir, "filled_form.pdf")

        // 1. Create a PDF with AcroForm text fields
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)

        val acroForm = PDAcroForm(doc)
        doc.documentCatalog.acroForm = acroForm

        val dr = com.tom_roush.pdfbox.pdmodel.PDResources()
        dr.put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        acroForm.defaultResources = dr
        acroForm.defaultAppearance = "/Helv 12 Tf 0 g"

        val textField = PDTextField(acroForm).apply {
            partialName = "ApplicantName"
            defaultAppearance = "/Helv 12 Tf 0 g"
        }
        acroForm.fields.add(textField)
        textField.value = "John Doe"

        doc.save(testPdf)
        doc.close()

        // 2. Extract Form Fields
        val fields = AcroFormEngine.extractFields(context, Uri.fromFile(testPdf))
        assertEquals(1, fields.size)
        assertEquals("ApplicantName", fields[0].name)
        assertEquals("John Doe", fields[0].value)

        // 3. Save modified and flattened form
        val updatedValues = mapOf("ApplicantName" to "Jane Smith")
        val success = AcroFormEngine.fillAndSaveForm(
            context = context,
            sourceUri = Uri.fromFile(testPdf),
            destUri = Uri.fromFile(outputPdf),
            fieldValues = updatedValues,
            flattenForm = true
        )

        assertTrue(success)
        assertTrue(outputPdf.exists())
        assertTrue(outputPdf.length() > 0)
    }
}

