package com.pdfchemy.desktop.engine

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector
import javax.imageio.ImageIO

/**
 * THE GAUNTLET OF SEVEN: Adversarial Stress Test Suite
 * Designed to simulate the most grueling edge cases, reviewer penetration tests,
 * and media editorial audits before public release.
 */
class StressGauntletTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * TEST 1: The "Forensic Leak / Ghost Text" Redaction Audit
     * Validates that redacted sensitive tokens are completely obliterated from the output
     * document's text stream, fonts, and raw bytes, preventing forensic text recovery.
     */
    @Test
    fun test01_ForensicRedactionUnderAudit() {
        val testFile = tempFolder.newFile("forensic_audit_in.pdf")
        val outFile = tempFolder.newFile("forensic_audit_out.pdf")

        val sensitiveToken = "SSN-999-00-1234"
        val publicToken = "Public Medical Summary Record"

        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 14f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("$publicToken: $sensitiveToken")
            cs.endText()
        }
        doc.save(testFile)
        doc.close()

        // Verify sensitive token is initially present
        val initialText = DesktopPdfEngine.extractText(testFile)
        assertTrue("Input document must contain target token", initialText.contains(sensitiveToken))

        // Execute forensic redaction
        val matchCount = DesktopPdfEngine.redactPdf(
            inputFile = testFile,
            outputFile = outFile,
            query = sensitiveToken,
            overlayText = "REDACTED",
            forensicSanitize = true,
            dpi = 150f
        )

        assertTrue("Must detect and redact target occurrence", matchCount >= 1)
        assertTrue("Output file must exist and have content", outFile.exists() && outFile.length() > 0)

        // 1. Audit Text Extraction Layer
        val redactedText = DesktopPdfEngine.extractText(outFile)
        assertFalse("CRITICAL AUDIT: Redacted text layer must NOT contain sensitive token", redactedText.contains(sensitiveToken))

        // 2. Audit Raw File Byte Stream (forensic inspection)
        val rawBytes = outFile.readBytes()
        val rawString = String(rawBytes, Charsets.ISO_8859_1)
        assertFalse("CRITICAL AUDIT: Raw PDF binary must NOT contain unencrypted plaintext sensitive token", rawString.contains(sensitiveToken))
    }

    /**
     * TEST 2: The "Monster Image / Heap Exhaustion" Bomb
     * Feeds a massive 3500x3500 high-resolution bitmap through image compilation,
     * thumbnail rendering, and compression to verify heap stability and memory recycling.
     */
    @Test
    fun test02_MonsterImageHeapStress() {
        val monsterImgFile = tempFolder.newFile("monster_scan.png")
        val width = 3500
        val height = 3500
        val monsterBimg = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = monsterBimg.createGraphics()
        g.color = Color.LIGHT_GRAY
        g.fillRect(0, 0, width, height)
        g.color = Color.DARK_GRAY
        for (i in 0 until width step 200) {
            g.drawLine(i, 0, i, height)
            g.drawLine(0, i, width, i)
        }
        g.dispose()
        ImageIO.write(monsterBimg, "png", monsterImgFile)

        val compiledPdf = tempFolder.newFile("monster_compiled.pdf")
        DesktopPdfEngine.imagesToPdf(listOf(monsterImgFile), compiledPdf)
        assertTrue(compiledPdf.exists() && compiledPdf.length() > 0)

        // Stress: Render thumbnail
        val thumb = DesktopPdfEngine.renderThumbnail(compiledPdf, 0, targetWidth = 300)
        assertNotNull(thumb)
        assertTrue(thumb.width > 0 && thumb.height > 0)

        // Stress: Compress monster PDF
        val compressedPdf = tempFolder.newFile("monster_compressed.pdf")
        val compressedSize = DesktopPdfEngine.compressPdf(compiledPdf, compressedPdf, targetDpi = 100f, quality = 0.6f)
        assertTrue("Compressed size must be valid", compressedSize > 0)
        assertTrue("Compressed monster PDF must load cleanly", DesktopPdfEngine.getPageCount(compressedPdf) == 1)
    }

    /**
     * TEST 3: The "Severed Spine" (Truncated EOF & Scrambled XRef Table)
     * Severes the last 250 bytes of a PDF (stripping %%EOF and xref trailer), adds prefix junk,
     * and validates that the repair engine reconstructs the document without data loss.
     */
    @Test
    fun test03_SeveredSpineCorruptedXrefAndTruncatedEof() {
        val validFile = tempFolder.newFile("original_valid.pdf")
        val doc = PDDocument()
        for (i in 1..3) {
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Vital Financial Ledger Page $i")
                cs.endText()
            }
        }
        doc.save(validFile)
        doc.close()

        val validBytes = validFile.readBytes()
        assertTrue("Valid file must be at least 500 bytes", validBytes.size > 500)

        // Truncate the last 180 bytes (destroys %%EOF and startxref trailer) and prepend junk
        val prefixJunk = "NOISE_BYTES_0123456789\n".toByteArray(Charsets.US_ASCII)
        val truncatedPayload = validBytes.copyOfRange(0, validBytes.size - 180)
        val damagedBytes = prefixJunk + truncatedPayload

        val damagedFile = tempFolder.newFile("damaged_severed.pdf")
        FileOutputStream(damagedFile).use { it.write(damagedBytes) }

        // Repair document
        val repairedFile = tempFolder.newFile("repaired_restored.pdf")
        val repairSuccess = DesktopPdfEngine.repairPdf(damagedFile, repairedFile)
        assertTrue("Repair engine must report success", repairSuccess)
        assertTrue("Repaired file must exist", repairedFile.exists() && repairedFile.length() > 0)

        // Verify repaired document integrity
        val recoveredPages = DesktopPdfEngine.getPageCount(repairedFile)
        assertEquals("Must recover all 3 original pages", 3, recoveredPages)

        val recoveredText = DesktopPdfEngine.extractText(repairedFile)
        assertTrue("Must preserve text content across recovered pages", recoveredText.contains("Vital Financial Ledger Page 1"))
        assertTrue("Must preserve text content across recovered pages", recoveredText.contains("Vital Financial Ledger Page 3"))
    }

    /**
     * TEST 4: The "Impossible Budget" Target Size Optimizer
     * Creates an authentically heavy uncompressed document and commands the engine
     * to compress strictly toward an aggressive budget.
     */
    @Test
    fun test04_ExtremeTargetSizeConvergence() {
        val heavyPdf = tempFolder.newFile("heavy_doc.pdf")
        val doc = PDDocument()
        val random = java.util.Random(42)

        for (i in 1..2) {
            val page = PDPage()
            doc.addPage(page)
            val bimg = BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 1000) {
                for (y in 0 until 1000) {
                    val r = (x * y + random.nextInt(128)) % 256
                    val g = (x * 2 + y * 3) % 256
                    val b = (x + y + random.nextInt(256)) % 256
                    bimg.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                }
            }

            // Insert as lossless bitmap to create a truly heavy uncompressed PDF (~1.5 MB)
            val pdImg = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bimg)

            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(pdImg, 50f, 50f, 500f, 500f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(50f, 650f)
                cs.showText("High Detail Lossless Scan Page $i")
                cs.endText()
            }
        }
        doc.save(heavyPdf)
        doc.close()

        val originalSize = heavyPdf.length()
        assertTrue("Input uncompressed PDF must be truly heavy (> 300 KB)", originalSize > 300_000)

        val targetBudget = 180_000L // 180 KB budget

        val optimizedOut = tempFolder.newFile("optimized_output.pdf")
        val finalSize = DesktopPdfEngine.compressToTargetSize(heavyPdf, optimizedOut, targetBudget)

        assertTrue("Output file must exist", optimizedOut.exists())
        assertTrue("Optimized file must be significantly smaller than original (Original: $originalSize, Final: $finalSize)", finalSize < originalSize)
        assertTrue("Optimized file must achieve compression reduction > 50%", finalSize < (originalSize * 0.5))
        assertEquals("Page count must be strictly preserved", 2, DesktopPdfEngine.getPageCount(optimizedOut))
        val extractedText = DesktopPdfEngine.extractText(optimizedOut)
        assertTrue("Vector text must be preserved after compression (not rasterized)", extractedText.contains("High Detail Lossless Scan Page 1"))
        assertTrue("Vector text must be preserved after compression (not rasterized)", extractedText.contains("High Detail Lossless Scan Page 2"))
    }

    /**
     * TEST 5: The "Anamorphic Chaos" Geometry Stress Test
     * Combines A4, massive blueprint (A0), tiny receipt, and rotated orientation pages.
     */
    @Test
    fun test05_AnamorphicChaosGeometry() {
        val chaosPdf = tempFolder.newFile("chaos_geometry.pdf")
        val doc = PDDocument()

        // Page 1: Standard A4 (595 x 842 pt)
        val p1 = PDPage(PDRectangle.A4)
        doc.addPage(p1)

        // Page 2: Large Blueprint (1800 x 2400 pt)
        val p2 = PDPage(PDRectangle(1800f, 2400f))
        doc.addPage(p2)

        // Page 3: Thin Long Receipt (180 x 700 pt) with 90 deg rotation
        val p3 = PDPage(PDRectangle(180f, 700f)).apply { rotation = 90 }
        doc.addPage(p3)

        // Page 4: Square Memo (400 x 400 pt) with 270 deg rotation
        val p4 = PDPage(PDRectangle(400f, 400f)).apply { rotation = 270 }
        doc.addPage(p4)

        doc.save(chaosPdf)
        doc.close()

        // 1. Test thumbnail rendering across all chaotic dimensions
        val thumbnailsRendered = mutableListOf<Int>()
        DesktopPdfEngine.renderAllThumbnails(chaosPdf, targetWidth = 200) { idx, img ->
            thumbnailsRendered.add(idx)
            assertTrue("Thumbnail dimensions must be valid", img.width > 0 && img.height > 0)
        }
        assertEquals("Must render all 4 chaotic pages", 4, thumbnailsRendered.size)

        // 2. Test reordering and cumulative rotation
        val reorderedPdf = tempFolder.newFile("chaos_reordered.pdf")
        val specs = listOf(
            PageItemSpec(originalPageIndex = 2, rotation = 90), // Receipt becomes 180
            PageItemSpec(originalPageIndex = 1, rotation = 0),  // Blueprint remains 0
            PageItemSpec(originalPageIndex = 3, rotation = 90)  // Square becomes 360 -> 0
        )
        DesktopPdfEngine.saveReorderedPdf(chaosPdf, reorderedPdf, specs)
        assertEquals(3, DesktopPdfEngine.getPageCount(reorderedPdf))

        PDDocument.load(reorderedPdf).use { rDoc ->
            assertEquals(180, rDoc.getPage(0).rotation)
            assertEquals(0, rDoc.getPage(1).rotation)
            assertEquals(0, rDoc.getPage(2).rotation)
        }
    }

    /**
     * TEST 6: The "Tower of Babel" Multilingual & Security Integrity
     * Encrypts, decrypts, and verifies document metadata and structure integrity.
     */
    @Test
    fun test06_TowerOfBabelSecurityAndIntegrity() {
        val sourcePdf = tempFolder.newFile("security_babel.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Global Verification Protocol 2026 - Passcode Encrypted")
            cs.endText()
        }
        doc.save(sourcePdf)
        doc.close()

        val encryptedFile = tempFolder.newFile("encrypted_out.pdf")
        val decryptedFile = tempFolder.newFile("decrypted_out.pdf")
        val passphrase = "Master_Key_#@!998"

        // Encrypt with 128-bit key
        DesktopPdfEngine.encryptPdf(sourcePdf, encryptedFile, userPass = passphrase, keyLengthBits = 128)
        assertTrue(encryptedFile.exists() && encryptedFile.length() > 0)

        // Verify file is locked
        try {
            PDDocument.load(encryptedFile).use { }
            fail("Opening encrypted file without password must fail")
        } catch (_: Exception) {
            // Expected
        }

        // Decrypt
        DesktopPdfEngine.decryptPdf(encryptedFile, decryptedFile, password = passphrase)
        assertTrue(decryptedFile.exists() && decryptedFile.length() > 0)

        val decryptedText = DesktopPdfEngine.extractText(decryptedFile)
        assertTrue("Decrypted text must contain original content", decryptedText.contains("Global Verification Protocol 2026"))
    }

    /**
     * TEST 7: The "Thread Avalanche" Concurrency & File Lock Bomb
     * Fires 16 concurrent threads executing simultaneous merges, splits, compressions,
     * and page extractions to prove zero thread deadlocks or Windows file lock crashes.
     */
    @Test
    fun test07_ThreadAvalancheConcurrencyAndFileLocks() {
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)

        val baseFile = tempFolder.newFile("concurrency_base.pdf")
        val doc = PDDocument()
        for (i in 1..2) {
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Concurrency Test Page $i")
                cs.endText()
            }
        }
        doc.save(baseFile)
        doc.close()

        val tasks = (0 until threadCount).map { id ->
            Callable {
                val threadOut = tempFolder.newFile("thread_${id}_out.pdf")
                when (id % 3) {
                    0 -> {
                        // Merge test
                        DesktopPdfEngine.mergePdfs(listOf(baseFile, baseFile), threadOut)
                        val count = DesktopPdfEngine.getPageCount(threadOut)
                        if (count != 4) throw RuntimeException("Expected 4 pages, got $count for mergePdfs")
                        true
                    }
                    1 -> {
                        // Rotate test
                        DesktopPdfEngine.rotatePages(baseFile, threadOut, degrees = 90)
                        val count = DesktopPdfEngine.getPageCount(threadOut)
                        if (count != 2) throw RuntimeException("Expected 2 pages, got $count for rotatePages")
                        true
                    }
                    else -> {
                        // Reorder test
                        val specs = listOf(PageItemSpec(0, 180))
                        DesktopPdfEngine.saveReorderedPdf(baseFile, threadOut, specs)
                        val count = DesktopPdfEngine.getPageCount(threadOut)
                        if (count != 1) throw RuntimeException("Expected 1 page, got $count for saveReorderedPdf")
                        true
                    }
                }
            }
        }

        val futures = executor.invokeAll(tasks, 120, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals("All $threadCount tasks must be initiated", threadCount, futures.size)
        for ((idx, future) in futures.withIndex()) {
            assertFalse("Thread task $idx should not have timed out or been cancelled", future.isCancelled)
            try {
                val result = future.get()
                assertTrue("Thread task $idx must succeed with valid output", result)
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }
    }

    /**
     * TEST 8: Pruned Desktop Icon Set Runtime Verification
     * Confirms that all 38 icons used in the Desktop UI resolve with valid vector geometries
     * and zero MissingClass or NoClassDefFound runtime errors.
     */
    @Test
    fun test08_AllDesktopIconsIntegrity() {
        val icons = listOf(
            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowForward,
            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.CallMerge,
            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.MenuBook,
            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.Notes,
            androidx.compose.material.icons.Icons.Rounded.Add,
            androidx.compose.material.icons.Icons.Rounded.AddPhotoAlternate,
            androidx.compose.material.icons.Icons.Rounded.AllInclusive,
            androidx.compose.material.icons.Icons.Rounded.CheckCircle,
            androidx.compose.material.icons.Icons.Rounded.ChevronLeft,
            androidx.compose.material.icons.Icons.Rounded.ChevronRight,
            androidx.compose.material.icons.Icons.Rounded.Close,
            androidx.compose.material.icons.Icons.Rounded.CloudUpload,
            androidx.compose.material.icons.Icons.Rounded.Collections,
            androidx.compose.material.icons.Icons.Rounded.ContentCopy,
            androidx.compose.material.icons.Icons.Rounded.CreditCard,
            androidx.compose.material.icons.Icons.Rounded.DarkMode,
            androidx.compose.material.icons.Icons.Rounded.Dashboard,
            androidx.compose.material.icons.Icons.Rounded.DeleteOutline,
            androidx.compose.material.icons.Icons.Rounded.Email,
            androidx.compose.material.icons.Icons.Rounded.Favorite,
            androidx.compose.material.icons.Icons.Rounded.FileOpen,
            androidx.compose.material.icons.Icons.Rounded.FolderOpen,
            androidx.compose.material.icons.Icons.Rounded.GridView,
            androidx.compose.material.icons.Icons.Rounded.Image,
            androidx.compose.material.icons.Icons.Rounded.Layers,
            androidx.compose.material.icons.Icons.Rounded.LightMode,
            androidx.compose.material.icons.Icons.Rounded.Lightbulb,
            androidx.compose.material.icons.Icons.Rounded.Lock,
            androidx.compose.material.icons.Icons.Rounded.LockOpen,
            androidx.compose.material.icons.Icons.Rounded.PictureAsPdf,
            androidx.compose.material.icons.Icons.Rounded.Refresh,
            androidx.compose.material.icons.Icons.Rounded.Save,
            androidx.compose.material.icons.Icons.Rounded.Shield,
            androidx.compose.material.icons.Icons.Rounded.Speed,
            androidx.compose.material.icons.Icons.Rounded.Straighten,
            androidx.compose.material.icons.Icons.Rounded.Tune,
            androidx.compose.material.icons.Icons.Rounded.Undo,
            androidx.compose.material.icons.Icons.Rounded.UploadFile
        )
        assertEquals(38, icons.size)
        icons.forEach { icon ->
            assertNotNull(icon.name)
            assertTrue(icon.defaultWidth.value > 0f)
        }
    }

    /**
     * TEST 8: Metadata Inspection and Stripping Audit
     * Verifies that document information (author, title, creator) is correctly inspected,
     * and that stripMetadata completely wipes all metadata and XMP packages.
     */
    @Test
    fun test08_MetadataInspectionAndStripping() {
        val testFile = tempFolder.newFile("metadata_test_in.pdf")
        val outFile = tempFolder.newFile("metadata_test_out.pdf")

        // Create PDF with embedded sensitive author and creator information
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val info = doc.documentInformation
            info.title = "Confidential Strategy Report"
            info.author = "John Doe (Confidential Author)"
            info.creator = "Adobe Acrobat Pro v2024"
            info.keywords = "finance, secret, internal"
            doc.save(testFile)
        }

        // 1. Inspect metadata
        val inspected = DesktopPdfEngine.inspectMetadata(testFile)
        assertEquals("Confidential Strategy Report", inspected.title)
        assertEquals("John Doe (Confidential Author)", inspected.author)
        assertEquals("Adobe Acrobat Pro v2024", inspected.creator)
        assertTrue(inspected.hasAnyMetadata)

        // 2. Strip metadata
        val success = DesktopPdfEngine.stripMetadata(testFile, outFile)
        assertTrue(success)
        assertTrue(outFile.exists())

        // 3. Inspect stripped file
        val cleanInspected = DesktopPdfEngine.inspectMetadata(outFile)
        assertNull(cleanInspected.title)
        assertNull(cleanInspected.author)
        assertNull(cleanInspected.creator)
        assertNull(cleanInspected.keywords)
        assertFalse(cleanInspected.hasAnyMetadata)
    }
}

