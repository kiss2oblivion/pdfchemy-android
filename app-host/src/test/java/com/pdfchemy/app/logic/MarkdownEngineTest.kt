package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MarkdownEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testMarkdownToPdf_createsValidDocument() = runBlocking {
        val sampleMd = """
# Project Specifications
This is a paragraph with **bold** text and normal descriptions.

## Code Section
```
val app = PDFchemyTools()
app.start()
```

### Features List
- Fast on-device rendering
- 100% Offline privacy
- Multi-format support

> "Simplicity is prerequisite for reliability." — Edsger W. Dijkstra
        """.trimIndent()

        val destFile = File(context.cacheDir, "md_test_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val result = MarkdownEngine.markdownToPdf(
            context = context,
            markdownText = sampleMd,
            destPdfUri = destUri,
            documentTitle = "Markdown Studio Test"
        )

        assertTrue(result.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        val doc = PDDocument.load(destFile)
        assertTrue("Generated PDF must have at least 1 page", doc.numberOfPages >= 1)
        doc.close()
    }

    @Test
    fun testPdfToMarkdown_extractsStructuredText() = runBlocking {
        val sampleFile = File(context.cacheDir, "md_test_in.pdf")
        val sampleUri = Uri.fromFile(sampleFile)

        val createResult = MarkdownEngine.markdownToPdf(
            context = context,
            markdownText = "# Heading\nSample text content",
            destPdfUri = sampleUri,
            documentTitle = "Test"
        )
        assertTrue(createResult.isSuccess)

        val result = MarkdownEngine.pdfToMarkdown(context, sampleUri)
        assertTrue(result.isSuccess)
        val extractedMd = result.getOrThrow()
        assertTrue(extractedMd.isNotBlank())
    }
}
