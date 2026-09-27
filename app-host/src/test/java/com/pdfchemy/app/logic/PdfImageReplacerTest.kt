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
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfImageReplacerTest {

    private lateinit var context: Context
    private lateinit var testPdfFile: File
    private lateinit var testPdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        // Create a test PDF with an embedded image
        testPdfFile = File(context.cacheDir, "test_with_image.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        // Create 50x50 red bitmap
        val origBitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        origBitmap.eraseColor(Color.RED)

        val imageXObject = LosslessFactory.createFromImage(doc, origBitmap)
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(imageXObject, 100f, 100f, 100f, 100f)
        }

        FileOutputStream(testPdfFile).use { out ->
            doc.save(out)
        }
        doc.close()

        testPdfUri = Uri.fromFile(testPdfFile)
    }

    @Test
    fun testListEmbeddedImages_returnsDetectedImages() = runBlocking {
        val list = PdfImageReplacerEngine.listEmbeddedImages(context, testPdfUri)
        assertEquals("Should detect 1 embedded image", 1, list.size)
        val img = list.first()
        assertEquals(0, img.pageIndex)
        assertEquals(50, img.width)
        assertEquals(50, img.height)
        assertNotNull(img.thumbnailBitmap)
    }

    @Test
    fun testReplaceEmbeddedImage_swapsImageSuccessfully() = runBlocking {
        val list = PdfImageReplacerEngine.listEmbeddedImages(context, testPdfUri)
        val targetImg = list.first()

        val destFile = File(context.cacheDir, "output_swapped_image.pdf")
        val destUri = Uri.fromFile(destFile)

        // Create 100x100 blue replacement bitmap
        val replacementBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        replacementBitmap.eraseColor(Color.BLUE)

        val result = PdfImageReplacerEngine.replaceEmbeddedImage(
            context = context,
            sourcePdfUri = testPdfUri,
            destPdfUri = destUri,
            pageIndex = targetImg.pageIndex,
            resourceName = targetImg.resourceName,
            replacementBitmap = replacementBitmap,
            isLossless = true
        )

        assertTrue("Image replacement should succeed", result.isSuccess)
        assertTrue("Destination file should exist and have content", destFile.exists() && destFile.length() > 0)

        // Verify updated PDF has the replaced image dimensions
        val updatedList = PdfImageReplacerEngine.listEmbeddedImages(context, destUri)
        assertEquals(1, updatedList.size)
        assertEquals(100, updatedList.first().width)
        assertEquals(100, updatedList.first().height)
    }
}
