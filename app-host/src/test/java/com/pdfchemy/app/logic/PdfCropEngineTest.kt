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
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfCropEngineTest {

    private lateinit var context: Context
    private lateinit var testPdfFile: File
    private lateinit var testPdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        testPdfFile = File(context.cacheDir, "sample_crop_test.pdf")
        val doc = PDDocument()
        val page1 = PDPage(PDRectangle.A4)
        val page2 = PDPage(PDRectangle.A4)
        doc.addPage(page1)
        doc.addPage(page2)
        doc.save(testPdfFile)
        doc.close()

        testPdfUri = Uri.fromFile(testPdfFile)
    }

    @Test
    fun testDetectContentBounds_withWhiteBorders() {
        // Create a bitmap with black box in the center and wide white borders
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply { color = Color.BLACK }
        // Draw content from (100, 150) to (300, 450)
        canvas.drawRect(100f, 150f, 300f, 450f, paint)

        val bounds = PdfCropEngine.detectContentBounds(bitmap)
        assertTrue("Crop left should be > 0", bounds.left > 0.05f)
        assertTrue("Crop top should be > 0", bounds.top > 0.05f)
        assertTrue("Crop right should be < 1.0", bounds.right < 0.95f)
        assertTrue("Crop bottom should be < 1.0", bounds.bottom < 0.95f)
        assertTrue("Bounds must be valid", bounds.isValid())
    }

    @Test
    fun testCropPdf_customBounds() = runBlocking {
        val destFile = File(context.cacheDir, "cropped_output.pdf")
        val destUri = Uri.fromFile(destFile)

        val cropRect = NormalizedCropRect(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f)
        val result = PdfCropEngine.cropPdf(context, testPdfUri, destUri, cropRect, targetPageIndex = null)

        assertTrue(result.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        val doc = PDDocument.load(destFile)
        assertEquals(2, doc.numberOfPages)
        val page1 = doc.getPage(0)
        val page2 = doc.getPage(1)

        val expectedWidth = PDRectangle.A4.width * 0.8f
        val expectedHeight = PDRectangle.A4.height * 0.8f

        assertEquals(expectedWidth, page1.cropBox.width, 1.0f)
        assertEquals(expectedHeight, page1.cropBox.height, 1.0f)
        assertEquals(expectedWidth, page2.cropBox.width, 1.0f)
        assertEquals(expectedHeight, page2.cropBox.height, 1.0f)

        doc.close()
    }
}
