package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
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
class PdfMetadataEngineTest {

    private lateinit var context: Context
    private lateinit var testPdfFile: File
    private lateinit var testPdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        testPdfFile = File(context.cacheDir, "sample_metadata_test.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val info = PDDocumentInformation().apply {
            title = "Annual Financial Report"
            author = "John Doe"
            subject = "Q4 Financials"
            keywords = "Finance, Report, 2026"
            creator = "PDFchemy Tools"
            producer = "Internal Accounting System"
        }
        doc.documentInformation = info
        doc.save(testPdfFile)
        doc.close()

        testPdfUri = Uri.fromFile(testPdfFile)
    }

    @Test
    fun testReadMetadata() = runBlocking {
        val result = PdfMetadataEngine.readMetadata(context, testPdfUri)
        assertTrue(result.isSuccess)
        val meta = result.getOrThrow()

        assertEquals("Annual Financial Report", meta.title)
        assertEquals("John Doe", meta.author)
        assertEquals("Q4 Financials", meta.subject)
        assertEquals("Finance, Report, 2026", meta.keywords)
        assertEquals(1, meta.pageCount)
    }

    @Test
    fun testSanitizeMetadata_wipesAllProperties() = runBlocking {
        val destFile = File(context.cacheDir, "sanitized_metadata.pdf")
        val destUri = Uri.fromFile(destFile)

        val result = PdfMetadataEngine.writeOrSanitizeMetadata(
            context,
            testPdfUri,
            destUri,
            newMetadata = null,
            wipeAllMetadata = true
        )

        assertTrue(result.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        val readResult = PdfMetadataEngine.readMetadata(context, destUri)
        assertTrue(readResult.isSuccess)
        val sanitizedMeta = readResult.getOrThrow()

        assertEquals("", sanitizedMeta.title)
        assertEquals("", sanitizedMeta.author)
        assertEquals("", sanitizedMeta.subject)
        assertEquals("", sanitizedMeta.keywords)
        assertEquals("", sanitizedMeta.creator)
        assertEquals("", sanitizedMeta.producer)
        assertFalse(sanitizedMeta.hasXmpMetadata)
    }

    @Test
    fun testUpdateMetadata_updatesSpecificFields() = runBlocking {
        val destFile = File(context.cacheDir, "updated_metadata.pdf")
        val destUri = Uri.fromFile(destFile)

        val updatedInfo = DocumentMetadataInfo(
            title = "Revised Title",
            author = "Jane Smith",
            subject = "Updated Subject",
            keywords = "Tax, Legal",
            creator = "PDFchemy Tools Pro",
            producer = "PDFchemy Tools Engine"
        )

        val result = PdfMetadataEngine.writeOrSanitizeMetadata(
            context,
            testPdfUri,
            destUri,
            newMetadata = updatedInfo,
            wipeAllMetadata = false
        )

        assertTrue(result.isSuccess)

        val readResult = PdfMetadataEngine.readMetadata(context, destUri)
        assertTrue(readResult.isSuccess)
        val newMeta = readResult.getOrThrow()

        assertEquals("Revised Title", newMeta.title)
        assertEquals("Jane Smith", newMeta.author)
        assertEquals("Updated Subject", newMeta.subject)
        assertEquals("Tax, Legal", newMeta.keywords)
    }
}
