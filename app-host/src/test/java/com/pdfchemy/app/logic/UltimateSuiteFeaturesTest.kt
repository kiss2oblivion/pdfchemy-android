package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class UltimateSuiteFeaturesTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    private fun createSamplePdf(file: File, pageCount: Int, textPrefix: String): Uri {
        val doc = PDDocument()
        for (i in 0 until pageCount) {
            val page = PDPage(PDRectangle.LETTER)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(100f, 700f)
                cs.showText("$textPrefix - Page ${i + 1}")
                cs.endText()
            }
        }
        FileOutputStream(file).use { doc.save(it) }
        doc.close()
        return Uri.fromFile(file)
    }

    @Test
    fun testSignatureEngine_SaveLoadApply() {
        // 1. Create and save a signature bitmap
        val sigBmp = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sigBmp)
        canvas.drawColor(Color.TRANSPARENT)
        val paint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        canvas.drawLine(10f, 10f, 90f, 40f, paint)

        val saveOk = kotlinx.coroutines.runBlocking {
            SignatureEngine.saveSignature(context, "test_sig", sigBmp)
        }
        assertTrue("Signature saving should succeed", saveOk)

        // 2. Load signatures
        val sigList = kotlinx.coroutines.runBlocking {
            SignatureEngine.loadSignatures(context)
        }
        assertTrue("Should load saved signature", sigList.any { it.first == "test_sig" })

        // 3. Apply signature to PDF
        val srcFile = File(context.cacheDir, "test_sig_src.pdf")
        val dstFile = File(context.cacheDir, "test_sig_dst.pdf")
        val srcUri = createSamplePdf(srcFile, 2, "Contract Agreement")
        val dstUri = Uri.fromFile(dstFile)

        val stream = ByteArrayOutputStream()
        sigBmp.compress(Bitmap.CompressFormat.PNG, 100, stream)

        val placed = listOf(
            PlacedSignature(
                pageIndex = 0,
                xRatio = 0.5f,
                yRatio = 0.5f,
                widthRatio = 0.3f,
                heightRatio = 0.1f,
                bitmapBytes = stream.toByteArray(),
                dateStamp = "2026-08-25"
            )
        )

        val applyOk = kotlinx.coroutines.runBlocking {
            SignatureEngine.applySignatures(context, srcUri, dstUri, placed)
        }
        assertTrue("Applying signature to PDF should succeed", applyOk)
        assertTrue("Output file should exist and be > 0 bytes", dstFile.exists() && dstFile.length() > 0)
    }

    @Test
    fun testPdfPageOrganizer_ReorderRotateDuplicateBlank() {
        val srcFile = File(context.cacheDir, "test_org_src.pdf")
        val dstFile = File(context.cacheDir, "test_org_dst.pdf")
        val srcUri = createSamplePdf(srcFile, 3, "Page")
        val dstUri = Uri.fromFile(dstFile)

        // Actions:
        // 1. Page 2 (original index 1)
        // 2. Blank page
        // 3. Page 1 rotated 90 deg (original index 0)
        // 4. Duplicate Page 1 (original index 0)
        val actions = listOf(
            PageAction(originalPageIndex = 1, rotationDegrees = 0),
            PageAction(isBlank = true),
            PageAction(originalPageIndex = 0, rotationDegrees = 90),
            PageAction(originalPageIndex = 0, rotationDegrees = 0)
        )

        val ok = kotlinx.coroutines.runBlocking {
            PdfPageOrganizer.reorganizePages(context, srcUri, dstUri, actions)
        }
        assertTrue("Reorganizing pages should succeed", ok)

        // Verify output doc
        val outDoc = PDDocument.load(dstFile)
        assertEquals("Output document should have 4 pages", 4, outDoc.numberOfPages)
        assertEquals("Page 3 should have 90 degree rotation", 90, outDoc.getPage(2).rotation)
        outDoc.close()
    }

    @Test
    fun testPdfStampAndNumberEngine_WatermarkAndPageNumbers() {
        val srcFile = File(context.cacheDir, "test_stamp_src.pdf")
        val wmFile = File(context.cacheDir, "test_stamp_wm.pdf")
        val numFile = File(context.cacheDir, "test_stamp_num.pdf")
        val srcUri = createSamplePdf(srcFile, 3, "Report")

        // 1. Watermark
        val wmOptions = WatermarkOptions(
            text = "CONFIDENTIAL",
            rotationDegrees = 45f,
            opacity = 0.4f,
            isTiled = true
        )
        val wmOk = kotlinx.coroutines.runBlocking {
            PdfStampAndNumberEngine.addWatermark(context, srcUri, Uri.fromFile(wmFile), wmOptions)
        }
        assertTrue("Watermarking should succeed", wmOk)
        assertTrue("Watermarked file should exist", wmFile.exists() && wmFile.length() > 0)

        // 2. Page Numbers
        val numOptions = PageNumberOptions(
            position = NumberPosition.BOTTOM_RIGHT,
            format = NumberFormat.PAGE_X_OF_Y,
            skipFirstPage = true
        )
        val numOk = kotlinx.coroutines.runBlocking {
            PdfStampAndNumberEngine.addPageNumbers(context, Uri.fromFile(wmFile), Uri.fromFile(numFile), numOptions)
        }
        assertTrue("Adding page numbers should succeed", numOk)

        // Check text in numbered doc
        val numDoc = PDDocument.load(numFile)
        assertEquals(3, numDoc.numberOfPages)
        val stripper = PDFTextStripper()
        stripper.startPage = 2
        stripper.endPage = 2
        val page2Text = stripper.getText(numDoc)
        assertTrue("Page 2 should contain page number text", page2Text.contains("Page 2 of 3"))
        numDoc.close()
    }

    @Test
    fun testPdfLayoutEngine_ResizeAndNUp() {
        val srcFile = File(context.cacheDir, "test_layout_src.pdf")
        val resizeFile = File(context.cacheDir, "test_layout_resize.pdf")
        val srcUri = createSamplePdf(srcFile, 2, "Invoice")

        // Test Resize to A4
        val resizeOk = kotlinx.coroutines.runBlocking {
            PdfLayoutEngine.resizePages(context, srcUri, Uri.fromFile(resizeFile), TargetPaperSize.A4)
        }
        assertTrue("Resize to A4 should succeed", resizeOk)

        val resizedDoc = PDDocument.load(resizeFile)
        val box = resizedDoc.getPage(0).mediaBox
        assertEquals(TargetPaperSize.A4.widthPts, box.width, 0.5f)
        assertEquals(TargetPaperSize.A4.heightPts, box.height, 0.5f)
        resizedDoc.close()
    }

    @Test
    fun testPdfDiffEngine_CompareDocuments() {
        val file1 = File(context.cacheDir, "test_diff_doc1.pdf")
        val file2 = File(context.cacheDir, "test_diff_doc2.pdf")

        val uri1 = createSamplePdf(file1, 2, "Version Alpha")
        val uri2 = createSamplePdf(file2, 2, "Version Beta")

        val diffResult = kotlinx.coroutines.runBlocking {
            PdfDiffEngine.compareDocuments(context, uri1, uri2)
        }

        assertEquals(2, diffResult.totalPagesDoc1)
        assertEquals(2, diffResult.totalPagesDoc2)
        assertEquals("Both pages should have modified text", 2, diffResult.modifiedPages)
        assertTrue("Should detect added lines", diffResult.pageDiffs[0].textAddedLines.isNotEmpty())
    }
}
