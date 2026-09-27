package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfFontInspectorEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_fonts.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Typography Sample")
            cs.endText()
        }

        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testInspectFonts_detectsEmbeddedType1() = runBlocking {
        val result = PdfFontInspectorEngine.inspectFonts(context, samplePdfUri)
        assertTrue(result.isSuccess)
        val fonts = result.getOrThrow()
        assertTrue("Must detect at least 1 font", fonts.isNotEmpty())
        assertEquals("Type 1", fonts[0].formatType)
    }
}
