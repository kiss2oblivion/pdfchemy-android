package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class AcroFormAndRedactionAuditTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testCreateAcroFormSetsNeedAppearancesAndHandlesRotation() = runBlocking {
        val srcFile = File(context.cacheDir, "sample_acro_source.pdf")
        val doc = PDDocument()
        val page1 = PDPage(PDRectangle.A4) // 0 rotation
        doc.addPage(page1)

        val page2 = PDPage(PDRectangle.A4)
        page2.rotation = 90 // 90 rotation
        doc.addPage(page2)

        FileOutputStream(srcFile).use { doc.save(it) }
        doc.close()

        val destFile = File(context.cacheDir, "sample_acro_output.pdf")
        val fields = listOf(
            InteractiveFieldSpec(
                pageIndex = 0,
                name = "fullName",
                type = FormFieldType.TEXT,
                xRatio = 0.1f,
                yRatio = 0.2f,
                widthRatio = 0.4f,
                heightRatio = 0.05f,
                defaultValue = "John Doe"
            ),
            InteractiveFieldSpec(
                pageIndex = 0,
                name = "agreeTerms",
                type = FormFieldType.CHECKBOX,
                xRatio = 0.1f,
                yRatio = 0.3f,
                widthRatio = 0.05f,
                heightRatio = 0.05f,
                defaultValue = "Yes"
            ),
            InteractiveFieldSpec(
                pageIndex = 1,
                name = "rotatedNotes",
                type = FormFieldType.TEXT,
                xRatio = 0.1f,
                yRatio = 0.2f,
                widthRatio = 0.4f,
                heightRatio = 0.05f,
                defaultValue = "Rotated text"
            )
        )

        val success = AcroFormEngine.createAcroFormWithFields(
            context = context,
            sourceUri = Uri.fromFile(srcFile),
            destUri = Uri.fromFile(destFile),
            fields = fields
        )

        assertTrue("createAcroFormWithFields should succeed", success)
        assertTrue("Output file must exist", destFile.exists())

        FileInputStream(destFile).use { inStream ->
            val resultDoc = PDDocument.load(inStream)
            // Use getAcroForm(null) to inspect the serialized state without triggering PDFBox's AcroFormDefaultFixup
            val acroForm = resultDoc.documentCatalog.getAcroForm(null)
            assertNotNull("AcroForm must be present", acroForm)
            assertTrue("NeedAppearances must be true in the saved PDF", acroForm!!.needAppearances)

            val nameField = acroForm.getField("fullName")
            assertNotNull("fullName field must exist", nameField)

            val checkField = acroForm.getField("agreeTerms")
            assertNotNull("agreeTerms field must exist", checkField)

            val rotField = acroForm.getField("rotatedNotes")
            assertNotNull("rotatedNotes field on page 2 must exist", rotField)

            resultDoc.close()
        }
    }

    @Test
    fun testSearchRedactionTargetsRetainsInterWordSpaces() = runBlocking {
        val srcFile = File(context.cacheDir, "sample_redact_spaced.pdf")
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Emergency contact: +1 555-234-5678 for support.")
            cs.newLineAtOffset(0f, -30f)
            cs.showText("Card on file: 4111 2222 3333 4444 expires soon.")
            cs.endText()
        }

        FileOutputStream(srcFile).use { doc.save(it) }
        doc.close()

        val uri = Uri.fromFile(srcFile)

        // 1. Test multi-word query with space
        val multiWordResult = PdfRedactionEngine.searchRedactionTargets(
            context = context,
            pdfUri = uri,
            query = "Emergency contact",
            isRegex = false
        )
        assertTrue("Multi-word search should succeed", multiWordResult.isSuccess)
        val multiWordBoxes = multiWordResult.getOrThrow()
        assertEquals("Should find exactly 1 match for 'Emergency contact'", 1, multiWordBoxes.size)
        assertTrue("Bounding box must have positive width", multiWordBoxes[0].normalizedRect.width() > 0f)

        // 2. Test phone number pattern with spaces
        val phoneResult = PdfRedactionEngine.searchRedactionTargets(
            context = context,
            pdfUri = uri,
            query = RedactPattern.PHONE_NUMBERS.regex,
            isRegex = true
        )
        assertTrue("Phone number search should succeed", phoneResult.isSuccess)
        val phoneBoxes = phoneResult.getOrThrow()
        assertTrue("Should detect phone number with spaces", phoneBoxes.isNotEmpty())

        // 3. Test credit card pattern with spaces
        val cardResult = PdfRedactionEngine.searchRedactionTargets(
            context = context,
            pdfUri = uri,
            query = RedactPattern.CREDIT_CARD.regex,
            isRegex = true
        )
        assertTrue("Credit card search should succeed", cardResult.isSuccess)
        val cardBoxes = cardResult.getOrThrow()
        assertTrue("Should detect spaced credit card pattern", cardBoxes.isNotEmpty())
    }

    @Test
    fun testSearchRedactionOnRotatedAndCroppedDocuments() = runBlocking {
        val srcFile = File(context.cacheDir, "sample_redact_rotated_cropped.pdf")
        val doc = PDDocument()

        val page1 = PDPage(PDRectangle.A4) // 0 rotation
        doc.addPage(page1)
        PDPageContentStream(doc, page1).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Top secret unrotated document")
            cs.endText()
        }

        val page2 = PDPage(PDRectangle.A4)
        page2.rotation = 90 // 90 deg rotation
        doc.addPage(page2)
        PDPageContentStream(doc, page2).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Top secret ninety degree document")
            cs.endText()
        }

        val page3 = PDPage(PDRectangle.A4)
        page3.rotation = 180 // 180 deg rotation
        doc.addPage(page3)
        PDPageContentStream(doc, page3).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Top secret one-eighty degree document")
            cs.endText()
        }

        val page4 = PDPage(PDRectangle.A4)
        val mb = page4.mediaBox
        page4.cropBox = PDRectangle(mb.lowerLeftX + 20f, mb.lowerLeftY + 20f, mb.width - 40f, mb.height - 40f)
        doc.addPage(page4)
        PDPageContentStream(doc, page4).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Top secret cropped document")
            cs.endText()
        }

        FileOutputStream(srcFile).use { doc.save(it) }
        doc.close()

        val uri = Uri.fromFile(srcFile)
        val result = PdfRedactionEngine.searchRedactionTargets(
            context = context,
            pdfUri = uri,
            query = "Top secret",
            isRegex = false
        )

        assertTrue("Search should succeed", result.isSuccess)
        val targets = result.getOrThrow()
        assertEquals("Should find 'Top secret' across all 4 pages", 4, targets.size)
        for (i in 0 until 4) {
            val target = targets[i]
            assertEquals(i, target.pageIndex)
            assertTrue("Bounding box width must be positive", target.normalizedRect.width() > 0f)
            assertTrue("Bounding box height must be positive", target.normalizedRect.height() > 0f)
            assertTrue("left in [0,1]", target.normalizedRect.left in 0.0f..1.0f)
            assertTrue("right in [0,1]", target.normalizedRect.right in 0.0f..1.0f)
            assertTrue("top in [0,1]", target.normalizedRect.top in 0.0f..1.0f)
            assertTrue("bottom in [0,1]", target.normalizedRect.bottom in 0.0f..1.0f)
        }
    }

    @Test
    fun testSearchRedactionMultilineTargets() = runBlocking {
        val srcFile = File(context.cacheDir, "sample_redact_multiline.pdf")
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Confidential notice paragraph one.")
            cs.newLineAtOffset(0f, -25f)
            cs.showText("Confidential notice paragraph two.")
            cs.newLineAtOffset(0f, -25f)
            cs.showText("Confidential notice paragraph three.")
            cs.endText()
        }

        FileOutputStream(srcFile).use { doc.save(it) }
        doc.close()

        val uri = Uri.fromFile(srcFile)
        val result = PdfRedactionEngine.searchRedactionTargets(
            context = context,
            pdfUri = uri,
            query = "Confidential notice",
            isRegex = false
        )

        assertTrue("Multiline search should succeed", result.isSuccess)
        val targets = result.getOrThrow()
        assertEquals("Should find all 3 line occurrences", 3, targets.size)
        targets.forEach { target ->
            assertEquals(0, target.pageIndex)
            assertTrue("Bounding box width must be positive", target.normalizedRect.width() > 0f)
            assertTrue("Bounding box height must be positive", target.normalizedRect.height() > 0f)
        }
    }
}
