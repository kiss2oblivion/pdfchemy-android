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
class PdfHeaderFooterEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_to_stamp.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage())
        doc.addPage(PDPage())
        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testApplyHeaderFooter_DynamicMacros() = runBlocking {
        val destFile = File(context.cacheDir, "stamped_macro_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val config = StampConfig(
            templateText = "Page {page} of {total} - {date}",
            position = HeaderFooterPosition.FOOTER_CENTER,
            fontSize = 10f
        )

        val result = PdfHeaderFooterEngine.applyHeaderFooter(context, samplePdfUri, destUri, config)
        assertTrue(result.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        val doc = PDDocument.load(destFile)
        assertEquals(2, doc.numberOfPages)
        doc.close()
    }

    @Test
    fun testApplyHeaderFooter_LegalBatesStamping() = runBlocking {
        val destFile = File(context.cacheDir, "stamped_bates_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val config = StampConfig(
            position = HeaderFooterPosition.FOOTER_RIGHT,
            batesConfig = BatesConfig(
                enabled = true,
                prefix = "CONFIDENTIAL-",
                suffix = "-EXP",
                startNumber = 100,
                digits = 6
            )
        )

        val result = PdfHeaderFooterEngine.applyHeaderFooter(context, samplePdfUri, destUri, config)
        assertTrue(result.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        val doc = PDDocument.load(destFile)
        assertEquals(2, doc.numberOfPages)
        doc.close()
    }
}
