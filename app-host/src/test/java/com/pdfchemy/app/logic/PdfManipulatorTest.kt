package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfManipulatorTest {

    private lateinit var context: Context
    private lateinit var tempPdfFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        // Create a dummy PDF with 5 pages
        tempPdfFile = File(context.cacheDir, "test_document.pdf")
        val document = PDDocument()
        for (i in 0 until 5) {
            document.addPage(PDPage())
        }
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
    fun deletePages_removesCorrectPages() = kotlinx.coroutines.test.runTest {
        // Arrange
        val sourceUri = Uri.fromFile(tempPdfFile)
        val destFile = File(context.cacheDir, "output_document.pdf")
        val destUri = Uri.fromFile(destFile)
        val pageRange = "1, 3" // Should delete pages 1 and 3 (0-indexed 0 and 2)

        // Act
        PdfManipulator.deletePages(context, sourceUri, destUri, pageRange)

        // Assert
        val resultDoc = PDDocument.load(destFile)
        assertEquals(3, resultDoc.numberOfPages) // 5 - 2 = 3 pages remaining
        resultDoc.close()
        destFile.delete()
    }

    @Test
    fun mergePdfs_combinesMultiplePdfs() = kotlinx.coroutines.test.runTest {
        // Arrange
        val secondPdf = File(context.cacheDir, "second.pdf")
        val doc2 = PDDocument()
        for (i in 0 until 3) doc2.addPage(PDPage())
        doc2.save(secondPdf)
        doc2.close()

        val sourceUris = listOf(Uri.fromFile(tempPdfFile), Uri.fromFile(secondPdf))
        val destFile = File(context.cacheDir, "merged.pdf")

        // Act
        PdfManipulator.mergePdfs(context, sourceUris, Uri.fromFile(destFile))

        // Assert
        val resultDoc = PDDocument.load(destFile)
        assertEquals(8, resultDoc.numberOfPages) // 5 + 3 = 8
        resultDoc.close()
        
        secondPdf.delete()
        destFile.delete()
    }

    @Test
    fun splitPdf_createsMultipleDocuments() = kotlinx.coroutines.test.runTest {
        // Arrange
        val outputDir = File(context.cacheDir, "split_output")
        outputDir.mkdirs()
        val documentFile = DocumentFile.fromFile(outputDir)
        
        // Act (split pages 1, 2, and 4)
        PdfManipulator.splitPdf(context, Uri.fromFile(tempPdfFile), documentFile, "base", "1-2, 4")

        // Assert
        val files = outputDir.listFiles()
        assertNotNull(files)
        assertEquals(3, files!!.size)
        
        // Clean up
        files.forEach { it.delete() }
        outputDir.delete()
    }

    @Test
    fun rotatePdf_rotatesPagesCorrectly() = kotlinx.coroutines.test.runTest {
        // Arrange
        val sourceUri = Uri.fromFile(tempPdfFile)
        val destFile = File(context.cacheDir, "rotated.pdf")
        val destUri = Uri.fromFile(destFile)
        val pageRange = "1, 3" // Rotate pages 1 and 3

        // Act
        PdfManipulator.rotatePdf(context, sourceUri, destUri, 90, pageRange)

        // Assert
        val resultDoc = PDDocument.load(destFile)
        assertEquals(5, resultDoc.numberOfPages)
        // Pages are 0-indexed, so page 0 is page 1, and page 2 is page 3
        assertEquals(90, resultDoc.getPage(0).rotation)
        assertEquals(0, resultDoc.getPage(1).rotation)
        assertEquals(90, resultDoc.getPage(2).rotation)
        assertEquals(0, resultDoc.getPage(3).rotation)
        
        resultDoc.close()
        destFile.delete()
    }
}
