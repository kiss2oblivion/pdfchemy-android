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
import java.io.FileInputStream
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfRedactionEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_redact.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Account Number: 4532-8890-1123-9988 Confidential")
            cs.endText()
        }

        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testSearchRedactionTargets() = runBlocking {
        val searchResult = PdfRedactionEngine.searchRedactionTargets(
            context = context,
            inputStream = FileInputStream(samplePdfFile),
            query = "Confidential"
        )
        assertTrue(searchResult.isSuccess)
        val targets = searchResult.getOrThrow()
        assertTrue("Must find occurrence of keyword", targets.isNotEmpty())
        assertEquals(0, targets[0].pageIndex)
    }

    @Test
    fun testApplyRedactions_createsSanitizedFile() = runBlocking {
        val destFile = File(context.cacheDir, "redacted_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val manualBox = RedactionBox(pageIndex = 0, normalizedRect = android.graphics.RectF(0.1f, 0.1f, 0.5f, 0.2f), overlayLabel = "Account Number")
        val config = RedactionConfig(isBlackout = true, defaultOverlayText = "REDACTED")

        val result = PdfRedactionEngine.applyRedactions(
            context = context,
            inputStream = FileInputStream(samplePdfFile),
            outputStream = FileOutputStream(destFile),
            boxes = listOf(manualBox),
            config = config
        )

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
        assertTrue(destFile.exists() && destFile.length() > 0)
    }
}
