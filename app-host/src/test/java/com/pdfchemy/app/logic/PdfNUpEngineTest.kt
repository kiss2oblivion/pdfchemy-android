package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfNUpEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_slides.pdf")
        val doc = PDDocument()
        for (i in 0 until 8) {
            doc.addPage(PDPage())
        }
        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testGenerateNUpPdf_4Up() = runBlocking {
        val destFile = File(context.cacheDir, "nup_4up_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val config = NUpConfig(
            layout = NUpLayout.FOUR_UP,
            order = NUpOrder.HORIZONTAL,
            drawBorders = true
        )

        val result = PdfNUpEngine.generateNUpPdf(
            context = context,
            sourcePdfUri = samplePdfUri,
            destPdfUri = destUri,
            config = config
        )

        assertTrue(result.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        val doc = PDDocument.load(destFile)
        // 8 pages with 4-up -> 2 sheets
        assertEquals(2, doc.numberOfPages)
        doc.close()
    }

    @Test
    fun testGenerateNUpPdf_2Up() = runBlocking {
        val destFile = File(context.cacheDir, "nup_2up_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val config = NUpConfig(
            layout = NUpLayout.TWO_UP,
            order = NUpOrder.VERTICAL,
            drawBorders = false
        )

        val result = PdfNUpEngine.generateNUpPdf(
            context = context,
            sourcePdfUri = samplePdfUri,
            destPdfUri = destUri,
            config = config
        )

        assertTrue(result.isSuccess)
        val doc = PDDocument.load(destFile)
        // 8 pages with 2-up -> 4 sheets
        assertEquals(4, doc.numberOfPages)
        doc.close()
    }
}
