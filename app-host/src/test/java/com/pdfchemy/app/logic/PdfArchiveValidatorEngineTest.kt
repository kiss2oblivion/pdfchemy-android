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
class PdfArchiveValidatorEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_archive.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage())
        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testInspectPdfACompliance() = runBlocking {
        val result = PdfArchiveValidatorEngine.inspectPdfACompliance(context, samplePdfUri)
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertTrue(report.checks.isNotEmpty())
        assertTrue(report.complianceScore in 0..100)
    }
}
