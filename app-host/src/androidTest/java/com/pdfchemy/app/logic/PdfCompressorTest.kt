package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class PdfCompressorTest {

    private lateinit var context: Context
    private lateinit var testDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        testDir = File(context.cacheDir, "compression-test-" + java.util.UUID.randomUUID()).apply { check(mkdirs()) }
    }

    @After fun cleanup() { testDir.deleteRecursively() }

    @Test
    fun testCompressionReducesFileSize() = runBlocking {
        // 1. Create a dummy PDF with a large uncompressed image
        val sourceFile = File(testDir, "source.pdf")
        createDummyPdfWithImage(sourceFile)
        val sourceSize = sourceFile.length()
        System.err.println("DEBUG: Source size: $sourceSize bytes")
        assertTrue("Source file should not be empty", sourceSize > 0)

        // 2. Prepare destination file
        val destFile = File(testDir, "compressed.pdf")
        if (destFile.exists()) destFile.delete()

        // 3. Compress
        val result = PdfCompressor.compressPdf(
            context,
            Uri.fromFile(sourceFile),
            Uri.fromFile(destFile),
            quality = 0.01f // EXTREME compression
        )

        // 4. Verify
        if (result.isFailure) {
            System.err.println("Compression failed with: ${result.exceptionOrNull()}")
            result.exceptionOrNull()?.printStackTrace()
        }
        assertTrue("Compression should be successful", result.isSuccess)
        
        val report = result.getOrThrow()
        assertTrue("Report should indicate images were processed", report.imagesProcessed > 0)
        assertTrue("Source size in report should match", report.originalSize == sourceSize)

        assertTrue("Destination file should exist", destFile.exists())
        
        val destSize = destFile.length()
        System.err.println("DEBUG: Compressed size: $destSize bytes")
        assertTrue("High-entropy image fixture must shrink: $sourceSize -> $destSize", destSize in 1 until sourceSize)
        assertSameDocumentContent(sourceFile, destFile)
    }

    @Test
    fun testCompressPdfDeduplicatesSharedImagesOnAndroid() = runBlocking {
        val sourceFile = File(testDir, "shared_source.pdf")
        val doc = PDDocument()
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)
        val pdImage = LosslessFactory.createFromImage(doc, bitmap)

        for (i in 1..3) {
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(pdImage, 50f, 50f, 200f, 200f)
            }
        }
        FileOutputStream(sourceFile).use { doc.save(it) }
        doc.close()
        bitmap.recycle()

        val destFile = File(testDir, "shared_compressed.pdf")
        if (destFile.exists()) destFile.delete()

        val result = PdfCompressor.compressPdf(
            context,
            Uri.fromFile(sourceFile),
            Uri.fromFile(destFile),
            quality = 0.5f
        )

        assertTrue(result.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)
        PDDocument.load(destFile).use { cDoc ->
            org.junit.Assert.assertEquals(3, cDoc.numberOfPages)
            val p0Cos = cDoc.getPage(0).resources.xObjectNames.map { cDoc.getPage(0).resources.getXObject(it).cosObject }
            val p1Cos = cDoc.getPage(1).resources.xObjectNames.map { cDoc.getPage(1).resources.getXObject(it).cosObject }
            org.junit.Assert.assertEquals("Shared image COS object should be identical across pages", p0Cos, p1Cos)
        }
    }

    @Test
    fun committedWordCorpusPreservesContentAtCompressionPresets() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val fixtures = listOf("11-word-metadata.pdf", "12-word-links-bookmarks.pdf")
        for (name in fixtures) {
            val source = File(testDir, name)
            assets.open("vanguard-benign/$name").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            val original = source.readBytes()
            val analysis = PdfCompressor.analyzePdf(context, Uri.fromFile(source)).getOrThrow()
            PDDocument.load(source).use { assertEquals(it.numberOfPages, analysis.pageCount) }
            for (quality in listOf(0.25f, 0.75f)) {
                val output = File(testDir, "$quality-$name")
                val report = PdfCompressor.compressPdf(
                    context, Uri.fromFile(source), Uri.fromFile(output), quality
                ).getOrThrow()
                assertEquals("$name original size", original.size.toLong(), report.originalSize)
                assertArrayEquals("$name source mutated", original, source.readBytes())
                assertSameDocumentContent(source, output)
            }
        }
    }

    private fun assertSameDocumentContent(source: File, output: File) {
        assertTrue("Output must exist and be nonempty", output.isFile && output.length() > 0)
        PDDocument.load(source).use { before ->
            PDDocument.load(output).use { after ->
                assertEquals("Page count", before.numberOfPages, after.numberOfPages)
                for (i in 0 until before.numberOfPages) {
                    val a = before.getPage(i)
                    val b = after.getPage(i)
                    assertEquals("Page rotation $i", a.rotation, b.rotation)
                    assertEquals("Page width $i", a.mediaBox.width, b.mediaBox.width, 0.001f)
                    assertEquals("Page height $i", a.mediaBox.height, b.mediaBox.height, 0.001f)
                    assertEquals("Annotations $i", a.annotations.size, b.annotations.size)
                }
                val strip = PDFTextStripper()
                assertEquals("Extracted document text", strip.getText(before), PDFTextStripper().getText(after))
            }
        }
    }

    @Test
    fun testAdvancedCompressionOptions() {
        runBlocking {
            val sourceFile = File(testDir, "source_adv.pdf")
        createDummyPdfWithImage(sourceFile)

        val destFile = File(testDir, "dest_adv.pdf")
        if (destFile.exists()) destFile.delete()

        // Test Grayscale, Lossless, and Metadata Stripping together
        val result = PdfCompressor.compressPdf(
            context,
            Uri.fromFile(sourceFile),
            Uri.fromFile(destFile),
            quality = 0.5f,
            useGrayscale = true,
            useLossless = true,
            stripMetadata = true
        )

        assertTrue("Advanced compression should be successful", result.isSuccess)
        assertTrue("Destination file should exist", destFile.exists())
        assertTrue("Destination file should have content", destFile.length() > 0)

        // Read and verify metadata is stripped
        val doc = PDDocument.load(destFile)
        assertTrue("Title should be null/empty after metadata strip", doc.documentInformation.title.isNullOrEmpty())
        assertTrue("Author should be null/empty after metadata strip", doc.documentInformation.author.isNullOrEmpty())
        doc.close()

            sourceFile.delete()
            destFile.delete()
        }
    }

    private fun createDummyPdfWithImage(file: File) {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)

        document.documentInformation.title = "Compression fixture"
        document.documentInformation.author = "Synthetic test author"
        // Opaque deterministic high-entropy RGB pixels ensure a measurable lossy reduction.
        val bitmap = Bitmap.createBitmap(1600, 1600, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(42)
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            for (x in row.indices) row[x] = 0xff000000.toInt() or random.nextInt(0x1000000)
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        bitmap.setHasAlpha(false)

        // Add as lossless image
        val xImage = LosslessFactory.createFromImage(document, bitmap)
        PDPageContentStream(document, page).use { contentStream ->
            contentStream.drawImage(xImage, 50f, 50f, 500f, 500f)
        }

        FileOutputStream(file).use {
            document.save(it)
        }
        document.close()
        bitmap.recycle()
    }
}
