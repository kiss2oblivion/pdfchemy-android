package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.TextToPdfConverter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TextToPdfConverterTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testShortTextConversion() {
        runBlocking {
            val destFile = File(context.cacheDir, "short_text.pdf")
            if (destFile.exists()) destFile.delete()

            val text = "Hello World! This is a simple text-to-pdf conversion test."
            val result = TextToPdfConverter.convert(context, text, Uri.fromFile(destFile))

            assertTrue("Conversion should be successful", result.isSuccess)
            assertTrue("PDF file should be created", destFile.exists())

            // Verify using PDFBox
            val doc = PDDocument.load(destFile)
            assertEquals("Should have exactly 1 page", 1, doc.numberOfPages)
            doc.close()

            destFile.delete()
        }
    }

    @Test
    fun testMultiPageTextConversion() {
        runBlocking {
            val destFile = File(context.cacheDir, "long_text.pdf")
            if (destFile.exists()) destFile.delete()

            // Create a very long text that will span multiple pages
            val stringBuilder = StringBuilder()
            for (i in 1..200) {
                stringBuilder.append("Line $i: This is a long sentence repeating some text to force page wrapping and vertical bounds overflow.\n")
            }

            val result = TextToPdfConverter.convert(context, stringBuilder.toString(), Uri.fromFile(destFile))

            assertTrue("Conversion should be successful", result.isSuccess)
            assertTrue("PDF file should be created", destFile.exists())

            // Verify using PDFBox
            val doc = PDDocument.load(destFile)
            assertTrue("Should have more than 1 page", doc.numberOfPages > 1)
            doc.close()

            destFile.delete()
        }
    }
}
