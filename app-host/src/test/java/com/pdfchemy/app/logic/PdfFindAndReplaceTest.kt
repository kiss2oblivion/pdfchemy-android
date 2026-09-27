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
import com.tom_roush.pdfbox.text.PDFTextStripper
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
class PdfFindAndReplaceTest {

    private lateinit var context: Context
    private lateinit var testPdfFile: File
    private lateinit var testPdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        testPdfFile = File(context.cacheDir, "sample_find_replace.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
            cs.newLineAtOffset(50f, 750f)
            cs.showText("Contract Agreement for Client ACME_CORP.")
            cs.newLineAtOffset(0f, -30f)
            cs.showText("ACME_CORP agrees to all terms and conditions.")
            cs.endText()
        }

        FileOutputStream(testPdfFile).use { out ->
            doc.save(out)
        }
        doc.close()

        testPdfUri = Uri.fromFile(testPdfFile)
    }

    @Test
    fun testFindOccurrences_locatesMatchesAccurately() = runBlocking {
        val summary = PdfFindAndReplaceEngine.findOccurrences(
            context = context,
            pdfUri = testPdfUri,
            query = "ACME_CORP",
            matchCase = true
        )

        assertEquals("Should find 2 occurrences of ACME_CORP", 2, summary.totalMatches)
        assertEquals(1, summary.pagesAffected)
        assertEquals("ACME_CORP", summary.occurrences[0].matchedText)
        assertEquals("ACME_CORP", summary.occurrences[1].matchedText)
    }

    @Test
    fun testReplaceAll_failsSecurelyInHeadlessEnvironment() = runBlocking {
        val destFile = File(context.cacheDir, "output_replaced_text.pdf")
        val destUri = Uri.fromFile(destFile)

        val result = PdfFindAndReplaceEngine.replaceAll(
            context = context,
            sourcePdfUri = testPdfUri,
            destPdfUri = destUri,
            findText = "ACME_CORP",
            replaceText = "GLOBAL_ENTERPRISE",
            matchCase = true
        )

        assertTrue("Replace text should FAIL in headless environment for security", result.isFailure)
        assertTrue("Failure should be a SecurityException", result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun testReplaceAll_withUnicodeCharacters_failsSecurelyInHeadlessEnvironment() = runBlocking {
        val destFile = File(context.cacheDir, "output_replaced_unicode.pdf")
        val destUri = Uri.fromFile(destFile)

        val result = PdfFindAndReplaceEngine.replaceAll(
            context = context,
            sourcePdfUri = testPdfUri,
            destPdfUri = destUri,
            findText = "ACME_CORP",
            replaceText = "GLOBAL_ENTERPRISE_ROMÂNĂ_ȚȘ",
            matchCase = true
        )

        assertTrue("Replace text with Unicode should FAIL in headless environment for security", result.isFailure)
        assertTrue("Failure should be a SecurityException", result.exceptionOrNull() is SecurityException)
    }
}
