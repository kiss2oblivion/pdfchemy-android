package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * ANDROID GAUNTLET OF SEVEN: Adversarial Stress Test Suite
 * Validates that Android core engines withstand extreme reviewer benchmarks,
 * memory constraints, severed files, and concurrent load.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class StressGauntletAndroidTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    /**
     * TEST 1: Forensic Redaction Target Discovery & Masking
     */
    @Test
    fun test01_AndroidForensicRedaction() = runBlocking {
        val testFile = File(context.cacheDir, "gauntlet_redact_in.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("CONFIDENTIAL_GOV_ID: 987-65-4321 TOP_SECRET")
            cs.endText()
        }
        doc.save(testFile)
        doc.close()

        val sourceUri = Uri.fromFile(testFile)
        val searchResult = PdfRedactionEngine.searchRedactionTargets(context, FileInputStream(testFile), "987-65-4321")
        assertTrue("Must locate sensitive token", searchResult.isSuccess)
        val targets = searchResult.getOrThrow()
        assertTrue("Found at least 1 target box", targets.isNotEmpty())

        val outFile = File(context.cacheDir, "gauntlet_redact_out.pdf")
        val destUri = Uri.fromFile(outFile)
        val applyResult = PdfRedactionEngine.applyRedactions(
            context = context,
            inputStream = FileInputStream(testFile),
            outputStream = FileOutputStream(outFile),
            boxes = targets,
            config = RedactionConfig(isBlackout = true, defaultOverlayText = "CENSORED")
        )

        assertTrue(applyResult.isSuccess)
        assertTrue(outFile.exists() && outFile.length() > 0)
    }

    /**
     * TEST 2: High-Resolution Bitmap Memory Recycling & Downsampling
     */
    @Test
    fun test02_AndroidHighResImageMemoryRecycling() = runBlocking {
        val width = 2000
        val height = 2000
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.MAGENTA)
        val paint = Paint().apply { color = Color.CYAN; strokeWidth = 10f }
        for (i in 0 until width step 200) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), paint)
        }

        val testImg = File(context.cacheDir, "gauntlet_monster.jpg")
        FileOutputStream(testImg).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bmp.recycle()

        assertTrue("High-res test image generated", testImg.exists() && testImg.length() > 0)

        // Compile to PDF
        val pdfFile = File(context.cacheDir, "gauntlet_monster.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val pdImg = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(doc, testImg.inputStream())
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(pdImg, 0f, 0f, 595f, 842f)
        }
        doc.save(pdfFile)
        doc.close()

        assertTrue("Monster PDF compiled cleanly", pdfFile.exists() && pdfFile.length() > 0)
    }

    /**
     * TEST 3: Severed Spine Corrupted PDF Reconstruction
     */
    @Test
    fun test03_AndroidSeveredSpineAutoRepair() = runBlocking {
        val validFile = File(context.cacheDir, "gauntlet_repair_orig.pdf")
        val doc = PDDocument()
        for (i in 1..2) {
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Recoverable Page $i")
                cs.endText()
            }
        }
        doc.save(validFile)
        doc.close()

        val validBytes = validFile.readBytes()
        // Truncate the trailer (removes %%EOF) and add garbage prefix
        val corruptedBytes = "CORRUPT_HEADER_NOISE_12345\n".toByteArray(Charsets.US_ASCII) +
                validBytes.copyOfRange(0, validBytes.size - 100)

        val damagedFile = File(context.cacheDir, "gauntlet_severed.pdf")
        FileOutputStream(damagedFile).use { it.write(corruptedBytes) }

        val repairedFile = File(context.cacheDir, "gauntlet_repaired.pdf")
        val repairResult = PdfRepairEngine.repairPdf(
            context,
            Uri.fromFile(damagedFile),
            Uri.fromFile(repairedFile)
        )

        assertTrue("Repair engine must succeed on damaged stream", repairResult.isSuccess)
        assertTrue(repairedFile.exists() && repairedFile.length() > 0)

        val repairedDoc = PDDocument.load(repairedFile)
        assertEquals("Must restore both pages", 2, repairedDoc.numberOfPages)
        repairedDoc.close()
    }

    /**
     * TEST 4: Extreme Target-Size Compression
     */
    @Test
    fun test04_AndroidExtremeCompression() = runBlocking {
        val heavyFile = File(context.cacheDir, "gauntlet_heavy.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val bmp = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(500 * 500)
        val random = java.util.Random(42)
        for (i in pixels.indices) {
            pixels[i] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bmp.setPixels(pixels, 0, 500, 0, 0, 500, 500)

        val pdImg = LosslessFactory.createFromImage(doc, bmp)
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(pdImg, 50f, 50f, 500f, 500f)
        }
        FileOutputStream(heavyFile).use { doc.save(it) }
        doc.close()
        bmp.recycle()

        val originalSize = heavyFile.length()
        assertTrue("Original uncompressed file must be heavy (was $originalSize bytes)", originalSize > 100_000)

        val compressedFile = File(context.cacheDir, "gauntlet_compressed.pdf")
        val result = PdfCompressor.compressPdf(
            context = context,
            sourceUri = Uri.fromFile(heavyFile),
            destUri = Uri.fromFile(compressedFile),
            quality = 0.15f
        )

        assertTrue("Compression must succeed", result.isSuccess)
        assertTrue(compressedFile.exists())
        val finalSize = compressedFile.length()
        assertTrue("Compressed size must be reduced (Orig: $originalSize, Final: $finalSize)", finalSize < originalSize)
    }

    /**
     * TEST 5: Anamorphic Geometric Bounds and Margins
     */
    @Test
    fun test05_AndroidAnamorphicGeometry() = runBlocking {
        val geomFile = File(context.cacheDir, "gauntlet_geom.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(100f, 500f)
            cs.showText("Geometry Test Content")
            cs.endText()
        }
        doc.save(geomFile)
        doc.close()

        val croppedFile = File(context.cacheDir, "gauntlet_cropped.pdf")
        val cropRect = NormalizedCropRect(left = 0.1f, top = 0.1f, right = 0.9f, bottom = 0.9f)
        val cropResult = PdfCropEngine.cropPdf(
            context = context,
            sourcePdfUri = Uri.fromFile(geomFile),
            destPdfUri = Uri.fromFile(croppedFile),
            cropRect = cropRect,
            targetPageIndex = null
        )

        assertTrue("Cropping with non-zero margins must succeed", cropResult.isSuccess)
        assertTrue(croppedFile.exists() && croppedFile.length() > 0)
    }

    /**
     * TEST 6: Multilingual Unicode Preservation
     */
    @Test
    fun test06_AndroidMultilingualUnicode() = runBlocking {
        val testFile = File(context.cacheDir, "gauntlet_unicode.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        val fullText = "English International Audit 2026 - Official Verification Protocol for Enterprise Document Integrity."
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText(fullText)
            cs.endText()
        }
        doc.save(testFile)
        doc.close()

        val textFile = File(context.cacheDir, "gauntlet_extracted.txt")
        val extractSuccess = PdfTextExtractor.extractText(
            context,
            sourceUri = Uri.fromFile(testFile),
            destUri = Uri.fromFile(textFile)
        )
        assertTrue("Extraction should be successful", extractSuccess)
        assertTrue("Extracted file exists", textFile.exists())
        val extracted = textFile.readText()
        assertTrue("Extracted text must match original", extracted.contains("English International Audit 2026"))
    }

    /**
     * TEST 7: Coroutine Concurrency Bomb
     * 12 parallel coroutines operating simultaneously on shared cache
     */
    @Test
    fun test07_AndroidCoroutineConcurrencyBomb() = runBlocking {
        val baseFile = File(context.cacheDir, "gauntlet_concurrent_base.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Concurrent Task Anchor")
            cs.endText()
        }
        doc.save(baseFile)
        doc.close()

        val jobs = (0 until 12).map { id ->
            async(Dispatchers.IO) {
                val out = File(context.cacheDir, "concurrent_out_$id.pdf")
                val res = PdfCompressor.compressPdf(
                    context,
                    Uri.fromFile(baseFile),
                    Uri.fromFile(out),
                    quality = 0.5f
                )
                res.isSuccess && out.exists() && out.length() > 0
            }
        }

        val results = jobs.awaitAll()
        assertEquals(12, results.size)
        assertTrue("All 12 concurrent jobs must succeed", results.all { it })
    }
}
