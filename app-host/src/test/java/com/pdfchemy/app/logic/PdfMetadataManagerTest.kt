package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfMetadataManagerTest {

    private lateinit var context: Context
    private lateinit var tempPdfFile: File
    private lateinit var metadataManager: PdfMetadataManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        metadataManager = PdfMetadataManager()

        // Create a dummy PDF with metadata
        tempPdfFile = File(context.cacheDir, "test_metadata.pdf")
        val document = PDDocument()
        document.addPage(PDPage())
        
        val info = PDDocumentInformation()
        info.title = "Test Title"
        info.author = "Test Author"
        info.creator = "ShrinkPDF"
        document.documentInformation = info
        
        document.save(tempPdfFile)
        document.close()
    }

    @After
    fun tearDown() {
        if (tempPdfFile.exists()) {
            tempPdfFile.delete()
        }
    }

    @Test
    fun getMetadata_returnsCorrectData() = runTest {
        val uri = Uri.fromFile(tempPdfFile)
        val result = metadataManager.getMetadata(context, uri)
        
        assertTrue(result.isSuccess)
        val metadata = result.getOrThrow()
        assertEquals("Test Title", metadata.title)
        assertEquals("Test Author", metadata.author)
        assertEquals("ShrinkPDF", metadata.creator)
        assertEquals("", metadata.subject)
    }

    @Test
    fun updateMetadata_writesNewData() = runTest {
        val sourceUri = Uri.fromFile(tempPdfFile)
        val destFile = File(context.cacheDir, "updated_metadata.pdf")
        val destUri = Uri.fromFile(destFile)

        val newMetadata = PdfMetadata(
            title = "New Title",
            subject = "New Subject"
        )

        val result = metadataManager.updateMetadata(context, sourceUri, destUri, newMetadata)
        assertTrue(result.isSuccess)

        // Verify
        val resultDoc = PDDocument.load(destFile)
        val info = resultDoc.documentInformation
        assertEquals("New Title", info.title)
        assertEquals("New Subject", info.subject)
        
        // Given the logic, blanks overwrite with null
        assertNull(info.author) 
        
        resultDoc.close()
        destFile.delete()
    }

    @Test
    fun clearMetadata_removesAllStandardFields() = runTest {
        val sourceUri = Uri.fromFile(tempPdfFile)
        val destFile = File(context.cacheDir, "cleared_metadata.pdf")
        val destUri = Uri.fromFile(destFile)

        val result = metadataManager.clearMetadata(context, sourceUri, destUri)
        assertTrue(result.isSuccess)

        val resultDoc = PDDocument.load(destFile)
        val info = resultDoc.documentInformation
        assertNull(info.title)
        assertNull(info.author)
        assertNull(info.creator)
        
        resultDoc.close()
        destFile.delete()
    }

    @Test
    fun clearMetadataOverwrite_removesFieldsInPlace() = runTest {
        val uri = Uri.fromFile(tempPdfFile)
        
        val result = metadataManager.clearMetadataOverwrite(context, uri)
        assertTrue(result.isSuccess)

        val resultDoc = PDDocument.load(tempPdfFile)
        val info = resultDoc.documentInformation
        assertNull(info.title)
        assertNull(info.author)
        assertNull(info.creator)
        
        resultDoc.close()
    }
}
