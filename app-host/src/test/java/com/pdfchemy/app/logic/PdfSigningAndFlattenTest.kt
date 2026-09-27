package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfSigningAndFlattenTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testSignatureEngineApplyMultipleSignaturesAndDateStamp() = runBlocking {
        val testPdf = File(context.cacheDir, "test_sig_source.pdf")
        val doc = PDDocument()
        val page1 = PDPage()
        val page2 = PDPage()
        doc.addPage(page1)
        doc.addPage(page2)

        PDPageContentStream(doc, page1).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Contract Agreement Page 1")
            cs.endText()
        }

        PDPageContentStream(doc, page2).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Contract Agreement Page 2")
            cs.endText()
        }

        doc.save(testPdf)
        doc.close()

        // Create sample signature bitmaps
        val bmp1 = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        bmp1.eraseColor(Color.BLUE)
        val stream1 = ByteArrayOutputStream()
        bmp1.compress(Bitmap.CompressFormat.PNG, 100, stream1)
        val bytes1 = stream1.toByteArray()

        val bmp2 = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        bmp2.eraseColor(Color.RED)
        val stream2 = ByteArrayOutputStream()
        bmp2.compress(Bitmap.CompressFormat.PNG, 100, stream2)
        val bytes2 = stream2.toByteArray()

        val sig1 = PlacedSignature(
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.8f,
            widthRatio = 0.25f,
            heightRatio = 0.08f,
            bitmapBytes = bytes1,
            dateStamp = "2026-09-11"
        )

        val sig2 = PlacedSignature(
            pageIndex = 1,
            xRatio = 0.3f,
            yRatio = 0.85f,
            widthRatio = 0.25f,
            heightRatio = 0.08f,
            bitmapBytes = bytes2,
            dateStamp = "Approved 2026"
        )

        val destPdf = File(context.cacheDir, "test_sig_dest.pdf")
        val success = SignatureEngine.applySignatures(
            context = context,
            sourceUri = Uri.fromFile(testPdf),
            destUri = Uri.fromFile(destPdf),
            signatures = listOf(sig1, sig2)
        )

        assertTrue("applySignatures should succeed", success)
        assertTrue("Destination PDF should exist", destPdf.exists())
        assertTrue("Destination PDF should be larger than 0", destPdf.length() > 0)

        // Verify that date stamp was stamped onto the PDF
        val resultDoc = PDDocument.load(destPdf)
        assertEquals(2, resultDoc.numberOfPages)

        val stripper = PDFTextStripper()
        stripper.startPage = 1
        stripper.endPage = 1
        val textPage1 = stripper.getText(resultDoc)
        assertTrue("Page 1 should contain the signature date stamp", textPage1.contains("Signed: 2026-09-11"))

        stripper.startPage = 2
        stripper.endPage = 2
        val textPage2 = stripper.getText(resultDoc)
        assertTrue("Page 2 should contain the page 2 date stamp", textPage2.contains("Signed: Approved 2026"))

        resultDoc.close()
        bmp1.recycle()
        bmp2.recycle()
    }

    @Test
    fun testSignatureEngineStampBitmaps() {
        val checkmark = SignatureEngine.createMarkBitmap("✔", "#008800", 120)
        assertNotNull(checkmark)
        assertEquals(120, checkmark.width)
        assertEquals(120, checkmark.height)

        val stampBmp = SignatureEngine.createBusinessStampBitmap("APPROVED", "2026-09-11", "#008800", 300, 100)
        assertNotNull(stampBmp)
        assertEquals(300, stampBmp.width)
        assertEquals(100, stampBmp.height)

        checkmark.recycle()
        stampBmp.recycle()
    }

    @Test
    fun testPdfFlattenEngineFormsFlattening() = runBlocking {
        val sampleFormPdf = File(context.cacheDir, "test_form_for_flatten.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val acroForm = PDAcroForm(doc)
        doc.documentCatalog.acroForm = acroForm
        acroForm.defaultAppearance = "/Helv 12 Tf 0 g"
        val defaultResources = com.tom_roush.pdfbox.pdmodel.PDResources()
        defaultResources.put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
        acroForm.defaultResources = defaultResources

        val textField = PDTextField(acroForm)
        textField.partialName = "TaxId"
        textField.defaultAppearance = "/Helv 12 Tf 0 g"
        textField.value = "123-45-6789"
        acroForm.fields.add(textField)

        doc.save(sampleFormPdf)
        doc.close()

        val destFile = File(context.cacheDir, "test_form_flattened_out.pdf")
        val result = PdfFlattenEngine.flattenPdf(
            context = context,
            sourcePdfUri = Uri.fromFile(sampleFormPdf),
            destPdfUri = Uri.fromFile(destFile),
            flattenForms = true,
            flattenAnnotations = false
        )

        assertTrue(result.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        val verifiedDoc = PDDocument.load(destFile)
        val form = verifiedDoc.documentCatalog.acroForm
        assertTrue(form == null || form.fields.isEmpty())
        verifiedDoc.close()
    }

    @Test
    fun testPdfTableExtractorEngineMultiPageIsolation() = runBlocking {
        val multiPagePdf = File(context.cacheDir, "test_multipage_tables.pdf")
        val doc = PDDocument()

        // Page 1
        val page1 = PDPage()
        doc.addPage(page1)
        PDPageContentStream(doc, page1).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Page1ColA")
            cs.newLineAtOffset(150f, 0f)
            cs.showText("Page1ColB")
            cs.endText()

            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 650f)
            cs.showText("Page1ValA")
            cs.newLineAtOffset(150f, 0f)
            cs.showText("Page1ValB")
            cs.endText()
        }

        // Page 2 with IDENTICAL Y-coordinates (would collide in naive global Y-sort)
        val page2 = PDPage()
        doc.addPage(page2)
        PDPageContentStream(doc, page2).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Page2ColA")
            cs.newLineAtOffset(150f, 0f)
            cs.showText("Page2ColB")
            cs.endText()

            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 650f)
            cs.showText("Page2ValA")
            cs.newLineAtOffset(150f, 0f)
            cs.showText("Page2ValB")
            cs.endText()
        }

        doc.save(multiPagePdf)
        doc.close()

        val csv = PdfTableExtractorEngine.extractTablesToCsv(context, Uri.fromFile(multiPagePdf))
        assertTrue("CSV should not be blank", csv.isNotBlank())

        val p1ColIdx = csv.indexOf("Page1ColA")
        val p1ValIdx = csv.indexOf("Page1ValA")
        val p2ColIdx = csv.indexOf("Page2ColA")
        val p2ValIdx = csv.indexOf("Page2ValA")

        assertTrue("Page 1 Column should appear in CSV", p1ColIdx != -1)
        assertTrue("Page 1 Value should appear in CSV", p1ValIdx != -1)
        assertTrue("Page 2 Column should appear in CSV", p2ColIdx != -1)
        assertTrue("Page 2 Value should appear in CSV", p2ValIdx != -1)

        // Strict ordering verification: Page 1 must complete BEFORE Page 2 begins
        assertTrue(
            "Page 1 rows must appear entirely before Page 2 rows without interleaving",
            p1ColIdx < p1ValIdx && p1ValIdx < p2ColIdx && p2ColIdx < p2ValIdx
        )
    }
}
