package com.pdfchemy.app.jail

import com.google.gson.Gson
import com.pdfchemy.app.jail.engines.PdfSanitizerEngineWorker
import com.pdfchemy.app.logic.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WorkerProducerContractTest {

    private val gson = Gson()

    @Test
    fun imageAnalysisProducerRealSerializationMatchesValidatorContract() {
        // 1. Supported allowed ImageAnalysis producer instance
        val supportedAnalysis = ImageAnalysis(
            width = 1600,
            height = 1200,
            originalSizeBytes = 345_678L,
            mimeType = "image/jpeg",
            formatName = "JPEG",
            isSupported = true,
            validationStatus = ImageValidationStatus.ALLOWED,
            validationMessage = null,
            alternativeSuggestion = null,
            estimatedCompressedBytes = 180_000L,
            estimatedSavingsPercent = 48,
            qualityLoss = PerceivedQualityLoss.MINIMAL
        )

        // Real serialization through production Gson instance used in MigratedEngineDispatch
        val serializedSupported = gson.toJson(supportedAnalysis)
        val contractSupported = WorkerResponseValidator.validate("IMAGE_ANALYZE", serializedSupported)
        assertTrue(contractSupported is ImageAnalysisContract)
        val verifiedSupported = (contractSupported as ImageAnalysisContract).analysis
        assertEquals(1600, verifiedSupported.width)
        assertEquals(1200, verifiedSupported.height)
        assertEquals(PerceivedQualityLoss.MINIMAL, verifiedSupported.qualityLoss)
        assertEquals(ImageValidationStatus.ALLOWED, verifiedSupported.validationStatus)
        assertTrue(verifiedSupported.isSupported)

        // 2. Unsupported format ImageAnalysis producer instance (0x0 dimensions)
        val unsupportedAnalysis = ImageAnalysis(
            width = 0,
            height = 0,
            originalSizeBytes = 5_000L,
            mimeType = "image/svg+xml",
            formatName = "SVG",
            isSupported = false,
            validationStatus = ImageValidationStatus.DENIED_UNSUPPORTED_FORMAT,
            validationMessage = "Vector or animated formats (SVG) cannot be raster-compressed.",
            alternativeSuggestion = "Tip: Use 'Images to PDF' to convert photos or share the graphic directly.",
            estimatedCompressedBytes = 5_000L,
            estimatedSavingsPercent = 0,
            qualityLoss = PerceivedQualityLoss.NEGLIGIBLE
        )
        val serializedUnsupported = gson.toJson(unsupportedAnalysis)
        val contractUnsupported = WorkerResponseValidator.validate("IMAGE_ANALYZE", serializedUnsupported)
        assertTrue(contractUnsupported is ImageAnalysisContract)
        val verifiedUnsupported = (contractUnsupported as ImageAnalysisContract).analysis
        assertEquals(0, verifiedUnsupported.width)
        assertEquals(0, verifiedUnsupported.height)
        assertEquals(PerceivedQualityLoss.NEGLIGIBLE, verifiedUnsupported.qualityLoss)
        assertEquals(ImageValidationStatus.DENIED_UNSUPPORTED_FORMAT, verifiedUnsupported.validationStatus)
        assertFalse(verifiedUnsupported.isSupported)

        // 3. Corrupt image analysis (0x0 dimensions)
        val corruptAnalysis = ImageAnalysis(
            width = 0,
            height = 0,
            originalSizeBytes = 120L,
            mimeType = "",
            formatName = "Corrupt/Unknown",
            isSupported = false,
            validationStatus = ImageValidationStatus.DENIED_CORRUPT,
            validationMessage = "The selected file is corrupted or not a valid image.",
            alternativeSuggestion = "Please choose a valid JPG, PNG, WebP, or HEIC photo.",
            estimatedCompressedBytes = 120L,
            estimatedSavingsPercent = 0,
            qualityLoss = PerceivedQualityLoss.NEGLIGIBLE
        )
        val serializedCorrupt = gson.toJson(corruptAnalysis)
        val contractCorrupt = WorkerResponseValidator.validate("IMAGE_ANALYZE", serializedCorrupt)
        assertTrue(contractCorrupt is ImageAnalysisContract)
        val verifiedCorrupt = (contractCorrupt as ImageAnalysisContract).analysis
        assertEquals(0, verifiedCorrupt.width)
        assertEquals(0, verifiedCorrupt.height)
        assertEquals(ImageValidationStatus.DENIED_CORRUPT, verifiedCorrupt.validationStatus)
    }

    @Test
    fun sanitizerWorkerProducerRealSerializationMatchesValidatorContract() {
        val tempDir = File.createTempFile("producer_test_", "").apply { delete(); mkdirs() }
        try {
            // 1. Clean PDF real worker audit execution & validation
            val cleanPdf = File(tempDir, "clean.pdf")
            PDDocument().use { doc ->
                doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
                doc.save(cleanPdf)
            }
            val cleanResultJson = android.os.ParcelFileDescriptor.open(cleanPdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfSanitizerEngineWorker.audit(pfd)
            }
            val cleanContract = WorkerResponseValidator.validate("SANITIZE_AUDIT", cleanResultJson) as SanitizeAuditContract
            assertTrue("cleanResultJson was: $cleanResultJson", cleanContract.isClean)
            assertEquals(0, cleanContract.threatsFound)
            assertFalse(cleanContract.isEncrypted)
            assertFalse(cleanContract.parseFailed)
            assertEquals(0, cleanContract.jsCount)
            assertEquals(0, cleanContract.launchActionsCount)
            assertEquals(0, cleanContract.attachmentCount)
            assertEquals(0, cleanContract.uriCount)
            assertFalse(cleanContract.hasMetadata)

            // 2. Encrypted PDF real worker audit execution & validation
            val encryptedPdf = File(tempDir, "encrypted.pdf")
            PDDocument().use { doc ->
                doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
                val ap = AccessPermission()
                val spp = StandardProtectionPolicy("owner123", "user123", ap)
                spp.encryptionKeyLength = 128
                doc.protect(spp)
                doc.save(encryptedPdf)
            }
            val encryptedPfd = android.os.ParcelFileDescriptor.open(encryptedPdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val encryptedResultJson = try {
                PdfSanitizerEngineWorker.audit(encryptedPfd)
            } finally {
                encryptedPfd.close()
            }
            val encryptedContract = WorkerResponseValidator.validate("SANITIZE_AUDIT", encryptedResultJson) as SanitizeAuditContract
            assertFalse("Encrypted document must not be clean", encryptedContract.isClean)
            assertTrue("Encrypted document must report isEncrypted=true", encryptedContract.isEncrypted)
            assertFalse("Encrypted document must report parseFailed=false", encryptedContract.parseFailed)
            assertEquals(1, encryptedContract.threatsFound)
            assertEquals(0, encryptedContract.jsCount)
            assertEquals(0, encryptedContract.launchActionsCount)
            assertEquals(0, encryptedContract.attachmentCount)
            assertEquals(0, encryptedContract.uriCount)
            assertFalse(encryptedContract.hasMetadata)

            // 3. Corrupt file real worker audit execution & validation
            val corruptPdf = File(tempDir, "corrupt.pdf").apply { writeText("not a real pdf file") }
            val corruptPfd = android.os.ParcelFileDescriptor.open(corruptPdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val corruptResultJson = try {
                PdfSanitizerEngineWorker.audit(corruptPfd)
            } finally {
                corruptPfd.close()
            }
            val corruptContract = WorkerResponseValidator.validate("SANITIZE_AUDIT", corruptResultJson) as SanitizeAuditContract
            assertFalse("Corrupt document must not be clean", corruptContract.isClean)
            assertFalse("Corrupt document must report isEncrypted=false", corruptContract.isEncrypted)
            assertTrue("Corrupt document must report parseFailed=true", corruptContract.parseFailed)
            assertEquals(1, corruptContract.threatsFound)
            assertEquals(0, corruptContract.jsCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
