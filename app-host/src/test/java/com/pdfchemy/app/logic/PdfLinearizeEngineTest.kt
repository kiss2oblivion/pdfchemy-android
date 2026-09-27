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
class PdfLinearizeEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_linear.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage())
        doc.addPage(PDPage())
        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testCheckLinearized() = runBlocking {
        val checkRes = PdfLinearizeEngine.checkLinearized(context, samplePdfUri)
        assertTrue(checkRes.isSuccess)
        val status = checkRes.getOrThrow()
        assertEquals(2, status.pageCount)
    }

    @Test
    fun testOptimizeFastWebView() = runBlocking {
        val destFile = File(context.cacheDir, "optimized_stream.pdf")
        val destUri = Uri.fromFile(destFile)

        val optRes = PdfLinearizeEngine.optimizeFastWebView(context, samplePdfUri, destUri)
        assertTrue(optRes.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)
    }
}
