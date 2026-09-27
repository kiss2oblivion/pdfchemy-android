package com.pdfchemy.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.TextToPdfConverter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class TextToPdfConverterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testConvertTextToPdfCreatesValidFile() = runBlocking {
        // 1. Prepare data
        val sampleText = "Hello World!\nThis is a test of the Text-to-PDF conversion.\nIt should handle multiple lines."
        val destFile = File(context.cacheDir, "test_text.pdf")
        if (destFile.exists()) destFile.delete()

        // 2. Convert
        val result = TextToPdfConverter.convert(
            context,
            sampleText,
            Uri.fromFile(destFile)
        )

        // 3. Verify
        assertTrue("Conversion should be successful", result.isSuccess)
        assertTrue("Destination file should exist", destFile.exists())
        assertTrue("File should not be empty", destFile.length() > 0)

        // 4. Verify it's a valid PDF by loading it
        FileInputStream(destFile).use { fis ->
            val document = PDDocument.load(fis)
            assertTrue("Document should have at least one page", document.numberOfPages > 0)
            document.close()
        }
    }

    @Test
    fun testEmptyTextReturnsFailure() = runBlocking {
        val destFile = File(context.cacheDir, "empty.pdf")
        val result = TextToPdfConverter.convert(context, "", Uri.fromFile(destFile))
        assertTrue("Empty text should fail", result.isFailure)
    }
}
