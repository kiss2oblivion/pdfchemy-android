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
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfEditorTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    private fun createSamplePdf(file: File, pageCount: Int = 3) {
        val document = PDDocument()
        for (i in 0 until pageCount) {
            val page = PDPage()
            document.addPage(page)
            val contentStream = PDPageContentStream(document, page)
            contentStream.beginText()
            contentStream.setFont(PDType1Font.HELVETICA_BOLD, 18f)
            contentStream.newLineAtOffset(100f, 700f)
            contentStream.showText("Sample Page ${i + 1}")
            contentStream.endText()
            contentStream.close()
        }
        document.save(file)
        document.close()
    }

    @Test
    fun testRenderAnnotationOverlayBitmap() {
        val drawings = listOf(
            DrawingPath(
                points = listOf(DrawingPoint(0.1f, 0.1f), DrawingPoint(0.5f, 0.5f)),
                color = Color.RED,
                strokeWidth = 5f,
                isHighlighter = false
            ),
            DrawingPath(
                points = listOf(DrawingPoint(0.2f, 0.2f), DrawingPoint(0.8f, 0.2f)),
                color = Color.YELLOW,
                strokeWidth = 15f,
                isHighlighter = true
            )
        )
        val textAnnotations = listOf(
            TextAnnotation(
                text = "Confidential Review",
                xRatio = 0.2f,
                yRatio = 0.3f,
                fontSize = 18f,
                textColor = Color.BLUE,
                backgroundColor = Color.WHITE
            )
        )
        val stamps = listOf(
            StampAnnotation(
                type = StampType.APPROVED,
                xRatio = 0.5f,
                yRatio = 0.5f
            )
        )

        val mod = PageModification(
            pageIndex = 0,
            drawings = drawings,
            textAnnotations = textAnnotations,
            stamps = stamps
        )

        val overlayBmp = PdfEditor.renderAnnotationOverlayBitmap(mod, 800, 1100)
        assertNotNull("Overlay bitmap should not be null", overlayBmp)
        assertEquals(800, overlayBmp!!.width)
        assertEquals(1100, overlayBmp.height)
    }

    @Test
    fun testExportPdfWithAnnotations() = runBlocking {
        val srcFile = File(context.cacheDir, "test_annotations_src.pdf")
        val dstFile = File(context.cacheDir, "test_annotations_dst.pdf")
        createSamplePdf(srcFile, pageCount = 2)

        val mod = PageModification(
            pageIndex = 0,
            drawings = listOf(
                DrawingPath(
                    points = listOf(DrawingPoint(0.1f, 0.1f), DrawingPoint(0.3f, 0.3f)),
                    color = Color.BLACK,
                    strokeWidth = 4f
                )
            ),
            textAnnotations = listOf(
                TextAnnotation(
                    text = "Signed by John Doe",
                    xRatio = 0.1f,
                    yRatio = 0.8f
                )
            ),
            stamps = listOf(
                StampAnnotation(
                    type = StampType.PAID,
                    xRatio = 0.5f,
                    yRatio = 0.5f
                )
            )
        )

        val result = PdfEditor.exportModifiedPdf(
            context = context,
            sourceUri = Uri.fromFile(srcFile),
            destUri = Uri.fromFile(dstFile),
            modifications = mapOf(0 to mod)
        )

        assertTrue("Export modified PDF should succeed", result.isSuccess)
        assertTrue("Destination file should exist and not be empty", dstFile.exists() && dstFile.length() > 0)

        // Verify document can be opened and parsed
        val doc = PDDocument.load(dstFile)
        assertEquals(2, doc.numberOfPages)
        doc.close()
    }

    @Test
    fun testExportPdfPageRotationAndDeletion() = runBlocking {
        val srcFile = File(context.cacheDir, "test_rot_del_src.pdf")
        val dstFile = File(context.cacheDir, "test_rot_del_dst.pdf")
        createSamplePdf(srcFile, pageCount = 3)

        val mods = mapOf(
            0 to PageModification(pageIndex = 0, rotationDegrees = 90),
            1 to PageModification(pageIndex = 1, isDeleted = true) // Delete page 2
        )

        val result = PdfEditor.exportModifiedPdf(
            context = context,
            sourceUri = Uri.fromFile(srcFile),
            destUri = Uri.fromFile(dstFile),
            modifications = mods
        )

        assertTrue("Export with rotation and deletion should succeed", result.isSuccess)

        val doc = PDDocument.load(dstFile)
        assertEquals("Document should have 2 pages after deleting 1", 2, doc.numberOfPages)
        assertEquals("First page should have 90 degree rotation", 90, doc.getPage(0).rotation)
        doc.close()
    }

    @Test
    fun testPageModificationHelpers() {
        val emptyMod = PageModification(pageIndex = 0)
        assertFalse(emptyMod.hasChanges)

        val rotatedMod = PageModification(pageIndex = 0, rotationDegrees = 180)
        assertTrue(rotatedMod.hasChanges)

        val deletedMod = PageModification(pageIndex = 0, isDeleted = true)
        assertTrue(deletedMod.hasChanges)
    }

    @Test
    fun testExportPdfTrueRedactionFailsSecurelyInHeadlessEnvironment() = runBlocking {
        val srcFile = File(context.cacheDir, "test_true_redaction_src.pdf")
        val dstFile = File(context.cacheDir, "test_true_redaction_dst.pdf")

        // 1. Create source PDF with sensitive text on page 0 and normal text on page 1
        val doc = PDDocument()
        val page0 = PDPage()
        doc.addPage(page0)
        PDPageContentStream(doc, page0).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 18f)
            cs.newLineAtOffset(100f, 700f)
            cs.showText("TOP SECRET SSN 999-00-1111 CONFIDENTIAL")
            cs.endText()
        }

        val page1 = PDPage()
        doc.addPage(page1)
        PDPageContentStream(doc, page1).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 18f)
            cs.newLineAtOffset(100f, 700f)
            cs.showText("UNCLASSIFIED PUBLIC INFORMATION")
            cs.endText()
        }
        doc.save(srcFile)
        doc.close()

        // 2. Add RedactionBox over page 0
        val redactBox = RedactionBox(
            pageIndex = 0,
            normalizedRect = RectF(0.05f, 0.05f, 0.95f, 0.35f),
            overlayLabel = "REDACTED"
        )
        val mod0 = PageModification(
            pageIndex = 0,
            redactions = listOf(redactBox)
        )

        val result = PdfEditor.exportModifiedPdf(
            context = context,
            sourceUri = Uri.fromFile(srcFile),
            destUri = Uri.fromFile(dstFile),
            modifications = mapOf(0 to mod0)
        )
        
        // 3. Verify that the operation fails securely because PdfRenderer is unavailable in Robolectric
        assertTrue("Export with redactions should FAIL in headless environment for security", result.isFailure)
        assertTrue("Failure should be a SecurityException", result.exceptionOrNull() is SecurityException)
    }
}
