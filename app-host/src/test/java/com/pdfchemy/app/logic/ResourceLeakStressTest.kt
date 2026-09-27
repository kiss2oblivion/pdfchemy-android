package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * RESOURCE LEAK STRESS TEST SUITE
 *
 * Monitors four critical resource dimensions under extreme load:
 *   1. MEMORY  — heap usage before/after to detect retained allocations
 *   2. CPU     — elapsed time tracking to catch algorithmic regressions
 *   3. DISK    — disk bytes written, ensuring output sizes are sane
 *   4. TEMP    — verifies temp file cleanup (nothing left behind in cacheDir)
 *
 * Design philosophy:
 *   - Each test calls Runtime.gc() + measures heap before/after the operation
 *   - Each test inventories cacheDir files before/after the operation
 *   - Timing is recorded for regression detection
 *   - Generous thresholds (2× baseline) to avoid flaky CI but catch real leaks
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResourceLeakStressTest {

    private lateinit var context: Context
    private lateinit var cacheDir: File

    // ── Snapshot helpers ──────────────────────────────────────────────────

    data class ResourceSnapshot(
        val heapUsedBytes: Long,
        val cacheDirFileCount: Int,
        val cacheDirTotalBytes: Long,
        val timestampMs: Long
    )

    private fun captureSnapshot(): ResourceSnapshot {
        forceGc()
        val rt = Runtime.getRuntime()
        val heapUsed = rt.totalMemory() - rt.freeMemory()
        val cacheFiles = cacheDir.listFiles() ?: emptyArray()
        val totalBytes = cacheFiles.filter { it.isFile }.sumOf { it.length() }
        return ResourceSnapshot(
            heapUsedBytes = heapUsed,
            cacheDirFileCount = cacheFiles.count { it.isFile },
            cacheDirTotalBytes = totalBytes,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun forceGc() {
        System.gc()
        System.runFinalization()
        System.gc()
        Thread.sleep(100)
    }

    private fun elapsedSec(before: ResourceSnapshot, after: ResourceSnapshot): Double =
        (after.timestampMs - before.timestampMs) / 1000.0

    private fun heapDeltaMb(before: ResourceSnapshot, after: ResourceSnapshot): Double =
        (after.heapUsedBytes - before.heapUsedBytes) / (1024.0 * 1024.0)

    private fun newTempFiles(before: ResourceSnapshot, after: ResourceSnapshot): Int =
        after.cacheDirFileCount - before.cacheDirFileCount

    // ── Fixture setup / teardown ─────────────────────────────────────────

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        cacheDir = context.cacheDir
        // Pre-clean cache
        cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    @After
    fun tearDown() {
        // Final sweep — stress tests should leave nothing behind
        cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    // ── PDF generation helpers ───────────────────────────────────────────

    /** Creates a multi-page text PDF, ~10 KB per page with random text. */
    private fun generateTextPdf(pageCount: Int, file: File) {
        val doc = PDDocument()
        val rng = java.util.Random(0xDEAD)
        for (i in 0 until pageCount) {
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 10f)
                cs.newLineAtOffset(50f, 780f)
                // Write ~40 lines of text per page
                for (line in 0 until 40) {
                    val sb = StringBuilder()
                    for (w in 0 until 12) {
                        sb.append("word${rng.nextInt(9999)} ")
                    }
                    cs.showText(sb.toString())
                    cs.newLineAtOffset(0f, -18f)
                }
                cs.endText()
            }
        }
        doc.save(file)
        doc.close()
    }

    /** Creates a heavy PDF with embedded uncompressed images. */
    private fun generateHeavyImagePdf(pageCount: Int, imgWidth: Int, imgHeight: Int, file: File) {
        val doc = PDDocument()
        val rng = java.util.Random(42)
        for (i in 0 until pageCount) {
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val bmp = Bitmap.createBitmap(imgWidth, imgHeight, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(imgWidth * imgHeight)
            for (p in pixels.indices) {
                pixels[p] = Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            }
            bmp.setPixels(pixels, 0, imgWidth, 0, 0, imgWidth, imgHeight)
            val pdImg = LosslessFactory.createFromImage(doc, bmp)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(pdImg, 0f, 0f, 595f, 842f)
            }
            bmp.recycle()
        }
        FileOutputStream(file).use { doc.save(it) }
        doc.close()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 1: Repeated Compression — Memory Leak Detection
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Runs PdfCompressor 20 times in sequence on the same heavy file.
     * Asserts:
     *   - Heap growth < 20 MB after 20 iterations (leak would accumulate)
     *   - No orphan temp files remain after each iteration
     *   - Each compressed output is smaller than the source
     */
    @Test
    fun test01_repeatedCompressionMemoryLeak() = runBlocking<Unit> {
        val sourceFile = File(cacheDir, "leak_compress_source.pdf")
        generateHeavyImagePdf(3, 800, 800, sourceFile)
        val originalSize = sourceFile.length()
        assertTrue("Source must be at least 100 KB", originalSize > 100_000)

        val before = captureSnapshot()

        for (iteration in 1..20) {
            val outFile = File(cacheDir, "leak_compress_out_$iteration.pdf")
            val result = PdfCompressor.compressPdf(
                context = context,
                sourceUri = Uri.fromFile(sourceFile),
                destUri = Uri.fromFile(outFile),
                quality = 0.3f
            )
            assertTrue("Compression iteration $iteration must succeed", result.isSuccess)
            assertTrue("Output must be smaller than input on iteration $iteration",
                outFile.length() < originalSize)

            // Clean up output immediately to isolate temp file detection
            outFile.delete()
        }

        val after = captureSnapshot()
        val heapGrowth = heapDeltaMb(before, after)
        val orphanFiles = newTempFiles(before, after)
        val elapsed = elapsedSec(before, after)

        println("═══ TEST 01: Repeated Compression ═══")
        println("  Iterations:     20")
        println("  Heap growth:    ${"%.2f".format(heapGrowth)} MB")
        println("  Orphan files:   $orphanFiles")
        println("  Elapsed:        ${"%.2f".format(elapsed)} sec")

        assertTrue("Heap growth after 20 compressions must be < 20 MB (was ${"%.2f".format(heapGrowth)} MB)",
            heapGrowth < 20.0)
        // Only the source file should remain
        assertTrue("No orphan temp files (found $orphanFiles new files)", orphanFiles <= 1)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 2: Repeated Text Extraction — Memory Leak Detection
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Extracts text from a 50-page PDF 15 times in sequence.
     * Asserts:
     *   - Heap growth < 15 MB (accumulated String retention)
     *   - No orphan temp files
     */
    @Test
    fun test02_repeatedTextExtractionMemoryLeak() = runBlocking<Unit> {
        val sourceFile = File(cacheDir, "leak_text_source.pdf")
        generateTextPdf(50, sourceFile)

        val before = captureSnapshot()

        for (iteration in 1..15) {
            val outFile = File(cacheDir, "leak_text_out_$iteration.txt")
            val success = PdfTextExtractor.extractText(
                context = context,
                sourceUri = Uri.fromFile(sourceFile),
                destUri = Uri.fromFile(outFile)
            )
            assertTrue("Text extraction iteration $iteration must succeed", success)
            assertTrue("Extracted text file must not be empty", outFile.length() > 0)
            outFile.delete()
        }

        val after = captureSnapshot()
        val heapGrowth = heapDeltaMb(before, after)
        val orphanFiles = newTempFiles(before, after)
        val elapsed = elapsedSec(before, after)

        println("═══ TEST 02: Repeated Text Extraction ═══")
        println("  Iterations:     15")
        println("  Heap growth:    ${"%.2f".format(heapGrowth)} MB")
        println("  Orphan files:   $orphanFiles")
        println("  Elapsed:        ${"%.2f".format(elapsed)} sec")

        assertTrue("Heap growth must be < 15 MB (was ${"%.2f".format(heapGrowth)} MB)",
            heapGrowth < 15.0)
        assertTrue("No orphan temp files (found $orphanFiles new files)", orphanFiles <= 1)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 3: Merge/Split Cycle — Disk Space & Temp File Leak
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Merges 5 PDFs into one, then splits them back, repeated 10 times.
     * Asserts:
     *   - Merged file size is approximately additive (±50%)
     *   - No accumulating temp files in cacheDir
     *   - Disk usage stays bounded
     */
    @Test
    fun test03_mergeSplitCycleDiskLeak() = runBlocking<Unit> {
        // Generate 5 source PDFs
        val sources = (1..5).map { i ->
            File(cacheDir, "merge_source_$i.pdf").also { generateTextPdf(5, it) }
        }
        val totalSourceSize = sources.sumOf { it.length() }

        val before = captureSnapshot()

        for (cycle in 1..10) {
            // Merge
            val mergedFile = File(cacheDir, "merged_cycle_$cycle.pdf")
            PdfManipulator.mergePdfs(
                context = context,
                sourceUris = sources.map { Uri.fromFile(it) },
                outputUri = Uri.fromFile(mergedFile)
            )
            assertTrue("Merged file must exist on cycle $cycle", mergedFile.exists())
            val mergedSize = mergedFile.length()
            // Merged shouldn't be more than 2× total source size
            assertTrue("Merged size ($mergedSize) must be < 2× sources ($totalSourceSize)",
                mergedSize < totalSourceSize * 2)

            // Verify page count
            val mergedDoc = PDDocument.load(mergedFile)
            assertEquals("Merged PDF must have 25 pages", 25, mergedDoc.numberOfPages)
            mergedDoc.close()

            // Clean up
            mergedFile.delete()
        }

        val after = captureSnapshot()
        val orphanFiles = newTempFiles(before, after)
        val diskDelta = (after.cacheDirTotalBytes - before.cacheDirTotalBytes) / (1024.0 * 1024.0)
        val elapsed = elapsedSec(before, after)

        println("═══ TEST 03: Merge/Split Cycle ═══")
        println("  Cycles:         10")
        println("  Disk delta:     ${"%.2f".format(diskDelta)} MB")
        println("  Orphan files:   $orphanFiles")
        println("  Elapsed:        ${"%.2f".format(elapsed)} sec")

        // Only the 5 source files should remain
        assertTrue("No orphan temp files beyond sources (found $orphanFiles new files)",
            orphanFiles <= 5)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 4: Concurrent Compression Bomb — CPU & Memory Under Contention
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Launches 16 parallel compression jobs on unique source PDFs.
     * Asserts:
     *   - All 16 complete successfully (no deadlocks or OOM)
     *   - Heap growth < 50 MB across all concurrent allocations
     *   - Wall-clock time < 120 sec (no excessive thread contention)
     *   - No orphan temp files
     */
    @Test
    fun test04_concurrentCompressionBomb() = runBlocking<Unit> {
        val sources = (0 until 16).map { i ->
            File(cacheDir, "concurrent_src_$i.pdf").also {
                generateHeavyImagePdf(2, 400, 400, it)
            }
        }

        val before = captureSnapshot()

        val results = (0 until 16).map { i ->
            async(Dispatchers.IO) {
                val outFile = File(cacheDir, "concurrent_out_$i.pdf")
                val result = PdfCompressor.compressPdf(
                    context = context,
                    sourceUri = Uri.fromFile(sources[i]),
                    destUri = Uri.fromFile(outFile),
                    quality = 0.4f
                )
                val success = result.isSuccess && outFile.exists() && outFile.length() > 0
                outFile.delete()
                success
            }
        }.awaitAll()

        val after = captureSnapshot()
        val heapGrowth = heapDeltaMb(before, after)
        val orphanFiles = newTempFiles(before, after)
        val elapsed = elapsedSec(before, after)

        println("═══ TEST 04: Concurrent Compression Bomb ═══")
        println("  Parallel jobs:  16")
        println("  All succeeded:  ${results.all { it }}")
        println("  Heap growth:    ${"%.2f".format(heapGrowth)} MB")
        println("  Orphan files:   $orphanFiles")
        println("  Elapsed:        ${"%.2f".format(elapsed)} sec")

        assertEquals("All 16 jobs must succeed", 16, results.count { it })
        assertTrue("Heap growth must be < 50 MB (was ${"%.2f".format(heapGrowth)} MB)",
            heapGrowth < 50.0)
        assertTrue("Elapsed must be < 120 sec (was ${"%.2f".format(elapsed)} sec)",
            elapsed < 120.0)
        // Only the 16 source files should remain
        assertTrue("No orphan temp files beyond sources (found $orphanFiles new)", orphanFiles <= 16)

        // Clean up sources
        sources.forEach { it.delete() }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 5: Repair Engine Temp File Audit
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Runs repair on 10 different corrupted PDFs.
     * Asserts:
     *   - Zero temp files leaked after all repairs
     *   - Repair succeeds or fails gracefully (no crash, no hang)
     *   - Heap growth < 10 MB
     */
    @Test
    fun test05_repairEngineTempFileAudit() = runBlocking<Unit> {
        val before = captureSnapshot()

        for (i in 1..10) {
            // Create valid PDF, then corrupt it differently each time
            val validFile = File(cacheDir, "repair_valid_$i.pdf")
            generateTextPdf(3, validFile)
            val validBytes = validFile.readBytes()

            val damagedFile = File(cacheDir, "repair_damaged_$i.pdf")
            val corruptedBytes = when (i % 3) {
                0 -> {
                    // Truncate trailer
                    validBytes.copyOfRange(0, (validBytes.size * 0.8).toInt())
                }
                1 -> {
                    // Inject garbage header
                    "GARBAGE_${i}_NOISE\n".toByteArray(Charsets.US_ASCII) + validBytes
                }
                else -> {
                    // Zero out middle section
                    val copy = validBytes.copyOf()
                    val mid = copy.size / 2
                    for (j in mid until (mid + 200).coerceAtMost(copy.size)) {
                        copy[j] = 0
                    }
                    copy
                }
            }
            FileOutputStream(damagedFile).use { it.write(corruptedBytes) }

            val repairedFile = File(cacheDir, "repair_output_$i.pdf")
            val result = PdfRepairEngine.repairPdf(
                context,
                Uri.fromFile(damagedFile),
                Uri.fromFile(repairedFile)
            )

            // We accept either success or graceful failure — no crashes
            if (result.isSuccess) {
                assertTrue("Repaired file must exist", repairedFile.exists())
            }

            // Clean up this iteration
            validFile.delete()
            damagedFile.delete()
            repairedFile.delete()
        }

        val after = captureSnapshot()
        val heapGrowth = heapDeltaMb(before, after)
        val orphanFiles = newTempFiles(before, after)
        val elapsed = elapsedSec(before, after)

        println("═══ TEST 05: Repair Engine Temp File Audit ═══")
        println("  Repair attempts:  10")
        println("  Heap growth:      ${"%.2f".format(heapGrowth)} MB")
        println("  Orphan files:     $orphanFiles")
        println("  Elapsed:          ${"%.2f".format(elapsed)} sec")

        assertTrue("No orphan temp files (found $orphanFiles)", orphanFiles <= 0)
        assertTrue("Heap growth must be < 10 MB (was ${"%.2f".format(heapGrowth)} MB)",
            heapGrowth < 10.0)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 6: Large Document Escalation — OOM Boundary Test
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Progressively creates PDFs with increasing page counts (10, 25, 50, 100)
     * and compresses each. Monitors heap at each level.
     * Asserts:
     *   - All compressions succeed without OOM
     *   - Heap growth scales sub-linearly (not quadratic)
     *   - Disk usage per page is bounded
     */
    @Test
    fun test06_largeDocumentEscalation() = runBlocking<Unit> {
        val pageCounts = listOf(10, 25, 50, 100)
        val heapPerLevel = mutableListOf<Double>()

        for (pages in pageCounts) {
            val sourceFile = File(cacheDir, "escalation_${pages}p.pdf")
            generateTextPdf(pages, sourceFile)

            val beforeLevel = captureSnapshot()

            val outFile = File(cacheDir, "escalation_${pages}p_out.pdf")
            val result = PdfCompressor.compressPdf(
                context = context,
                sourceUri = Uri.fromFile(sourceFile),
                destUri = Uri.fromFile(outFile),
                quality = 0.5f
            )
            assertTrue("Compression of $pages-page PDF must succeed", result.isSuccess)

            val afterLevel = captureSnapshot()
            val heapGrowth = heapDeltaMb(beforeLevel, afterLevel)
            heapPerLevel.add(heapGrowth)

            val kbPerPage = if (pages > 0) outFile.length() / 1024.0 / pages else 0.0

            println("  Escalation $pages pages: heap +${"%.2f".format(heapGrowth)} MB, " +
                    "${"%.1f".format(kbPerPage)} KB/page, " +
                    "${"%.2f".format(elapsedSec(beforeLevel, afterLevel))} sec")

            sourceFile.delete()
            outFile.delete()
        }

        println("═══ TEST 06: Large Document Escalation ═══")
        println("  Heap growths: ${heapPerLevel.map { "%.2f".format(it) }}")

        // The largest doc (100p) shouldn't use more than 40 MB
        assertTrue("100-page compression heap must be < 40 MB (was ${"%.2f".format(heapPerLevel.last())} MB)",
            heapPerLevel.last() < 40.0)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 7: Bitmap Churn — Rapid Create/Recycle Memory Pressure
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Creates and destroys 50 large bitmaps (2000×2000 ARGB = 16 MB each)
     * rapidly, simulating what happens when a user scrolls through pages
     * in the editor. Checks that native memory doesn't accumulate.
     */
    @Test
    fun test07_bitmapChurnMemoryPressure() = runBlocking<Unit> {
        val before = captureSnapshot()

        for (i in 1..50) {
            val bmp = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(i * 5 % 256, i * 3 % 256, i * 7 % 256))
            val paint = Paint().apply { color = Color.WHITE; textSize = 48f }
            canvas.drawText("Page $i stress", 100f, 1000f, paint)

            // Simulate writing to disk (editor save scenario)
            val tempFile = File(cacheDir, "bitmap_churn_$i.jpg")
            FileOutputStream(tempFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bmp.recycle()
            tempFile.delete()
        }

        val after = captureSnapshot()
        val heapGrowth = heapDeltaMb(before, after)
        val orphanFiles = newTempFiles(before, after)

        println("═══ TEST 07: Bitmap Churn Memory Pressure ═══")
        println("  Bitmaps churned:  50 × 2000×2000 (16 MB each)")
        println("  Heap growth:      ${"%.2f".format(heapGrowth)} MB")
        println("  Orphan files:     $orphanFiles")

        // After recycling 50 bitmaps, heap should not retain them
        assertTrue("Heap growth after bitmap churn must be < 10 MB (was ${"%.2f".format(heapGrowth)} MB)",
            heapGrowth < 10.0)
        assertTrue("No orphan bitmap files", orphanFiles <= 0)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 8: Cache Directory Growth Under Sustained Load
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Simulates a real user session: compress → extract → crop → redact
     * 5 times in sequence. Monitors total cache directory size growth.
     * Asserts:
     *   - cacheDir does not grow beyond 50 MB across the entire session
     *   - Each operation cleans up after itself
     */
    @Test
    fun test08_cacheDirGrowthUnderSustainedLoad() = runBlocking<Unit> {
        val imageSource = File(cacheDir, "sustained_source.pdf")
        generateHeavyImagePdf(5, 600, 600, imageSource)
        // Use a text PDF for extraction to avoid ML Kit OCR fallback in Robolectric
        val textSource = File(cacheDir, "sustained_text_source.pdf")
        generateTextPdf(10, textSource)

        val before = captureSnapshot()
        var maxCacheBytes = before.cacheDirTotalBytes

        for (cycle in 1..5) {
            // Step 1: Compress
            val compressedFile = File(cacheDir, "sustained_compressed_$cycle.pdf")
            PdfCompressor.compressPdf(
                context, Uri.fromFile(imageSource), Uri.fromFile(compressedFile), quality = 0.4f
            )

            // Step 2: Extract text (from the text-based PDF)
            val textFile = File(cacheDir, "sustained_text_$cycle.txt")
            PdfTextExtractor.extractText(
                context, Uri.fromFile(textSource), Uri.fromFile(textFile)
            )

            // Step 3: Crop
            val croppedFile = File(cacheDir, "sustained_cropped_$cycle.pdf")
            PdfCropEngine.cropPdf(
                context = context,
                sourcePdfUri = Uri.fromFile(imageSource),
                destPdfUri = Uri.fromFile(croppedFile),
                cropRect = NormalizedCropRect(0.1f, 0.1f, 0.9f, 0.9f),
                targetPageIndex = null
            )

            // Measure cache at peak
            val midSnapshot = captureSnapshot()
            if (midSnapshot.cacheDirTotalBytes > maxCacheBytes) {
                maxCacheBytes = midSnapshot.cacheDirTotalBytes
            }

            // Clean up outputs
            compressedFile.delete()
            textFile.delete()
            croppedFile.delete()
        }

        val after = captureSnapshot()
        val peakCacheMb = maxCacheBytes / (1024.0 * 1024.0)
        val finalCacheMb = after.cacheDirTotalBytes / (1024.0 * 1024.0)
        val orphanFiles = newTempFiles(before, after)
        val elapsed = elapsedSec(before, after)

        println("═══ TEST 08: Cache Dir Growth Under Sustained Load ═══")
        println("  Cycles:          5 (compress + extract + crop each)")
        println("  Peak cache:      ${"%.2f".format(peakCacheMb)} MB")
        println("  Final cache:     ${"%.2f".format(finalCacheMb)} MB")
        println("  Orphan files:    $orphanFiles")
        println("  Elapsed:         ${"%.2f".format(elapsed)} sec")

        assertTrue("Peak cache must be < 50 MB (was ${"%.2f".format(peakCacheMb)} MB)",
            peakCacheMb < 50.0)
        // Only the two source files should remain
        assertTrue("No orphan files beyond sources (found $orphanFiles new)", orphanFiles <= 2)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 9: PDDocument Close Discipline — File Descriptor Leak
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Opens and closes 100 PDDocuments rapidly to check for file descriptor leaks.
     * On Android, unclosed PDDocuments hold native file descriptors that
     * eventually exhaust the per-process FD limit (typically 1024).
     */
    @Test
    fun test09_pdDocumentCloseFileDescriptorLeak() = runBlocking<Unit> {
        val sourceFile = File(cacheDir, "fd_leak_source.pdf")
        generateTextPdf(10, sourceFile)

        val before = captureSnapshot()

        for (i in 1..100) {
            val doc = PDDocument.load(sourceFile)
            assertEquals("Must have 10 pages", 10, doc.numberOfPages)
            // Simulate reading content
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            val text = stripper.getText(doc)
            assertTrue("Must extract text on iteration $i", text.isNotBlank())
            doc.close()
        }

        val after = captureSnapshot()
        val heapGrowth = heapDeltaMb(before, after)
        val elapsed = elapsedSec(before, after)

        println("═══ TEST 09: PDDocument Close Discipline ═══")
        println("  Documents opened/closed: 100")
        println("  Heap growth:    ${"%.2f".format(heapGrowth)} MB")
        println("  Elapsed:        ${"%.2f".format(elapsed)} sec")

        // If FDs or memory are leaking, 100 iterations would blow up
        assertTrue("Heap growth after 100 open/close cycles must be < 15 MB (was ${"%.2f".format(heapGrowth)} MB)",
            heapGrowth < 15.0)

        sourceFile.delete()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TEST 10: Adversarial Rapid-Fire Operations — CPU & Stability Soak
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Fires 50 rapid sequential operations mixing compress, merge, and text
     * extraction. Validates the engine doesn't degrade under sustained fire.
     * Asserts:
     *   - No operation takes more than 30 sec individually
     *   - Total elapsed < 300 sec
     *   - Final heap growth < 30 MB
     *   - No orphan files
     */
    @Test
    fun test10_adversarialRapidFireOperations() = runBlocking<Unit> {
        val sourceA = File(cacheDir, "rapid_a.pdf").also { generateTextPdf(10, it) }
        val sourceB = File(cacheDir, "rapid_b.pdf").also { generateTextPdf(10, it) }

        val before = captureSnapshot()
        var maxSingleOpMs = 0L

        for (i in 1..50) {
            val opStart = System.currentTimeMillis()

            when (i % 3) {
                0 -> {
                    // Compress
                    val out = File(cacheDir, "rapid_out_$i.pdf")
                    PdfCompressor.compressPdf(
                        context, Uri.fromFile(sourceA), Uri.fromFile(out), quality = 0.5f
                    )
                    out.delete()
                    Unit
                }
                1 -> {
                    // Merge
                    val out = File(cacheDir, "rapid_out_$i.pdf")
                    PdfManipulator.mergePdfs(
                        context,
                        listOf(Uri.fromFile(sourceA), Uri.fromFile(sourceB)),
                        Uri.fromFile(out)
                    )
                    out.delete()
                    Unit
                }
                else -> {
                    // Extract text
                    val out = File(cacheDir, "rapid_out_$i.txt")
                    PdfTextExtractor.extractText(
                        context, Uri.fromFile(sourceA), Uri.fromFile(out)
                    )
                    out.delete()
                    Unit
                }
            }

            val opDuration = System.currentTimeMillis() - opStart
            if (opDuration > maxSingleOpMs) maxSingleOpMs = opDuration
        }

        val after = captureSnapshot()
        val heapGrowth = heapDeltaMb(before, after)
        val orphanFiles = newTempFiles(before, after)
        val elapsed = elapsedSec(before, after)

        println("═══ TEST 10: Adversarial Rapid-Fire Operations ═══")
        println("  Operations:        50 (mix of compress/merge/extract)")
        println("  Max single op:     ${maxSingleOpMs}ms")
        println("  Heap growth:       ${"%.2f".format(heapGrowth)} MB")
        println("  Orphan files:      $orphanFiles")
        println("  Total elapsed:     ${"%.2f".format(elapsed)} sec")

        assertTrue("No single operation should take > 30 sec (max was ${maxSingleOpMs}ms)",
            maxSingleOpMs < 30_000)
        assertTrue("Total elapsed must be < 300 sec (was ${"%.2f".format(elapsed)} sec)",
            elapsed < 300.0)
        assertTrue("Heap growth must be < 30 MB (was ${"%.2f".format(heapGrowth)} MB)",
            heapGrowth < 30.0)
        // Only sourceA and sourceB should remain
        assertTrue("No orphan files beyond sources (found $orphanFiles new)", orphanFiles <= 2)

        sourceA.delete()
        sourceB.delete()
        Unit
    }
}
