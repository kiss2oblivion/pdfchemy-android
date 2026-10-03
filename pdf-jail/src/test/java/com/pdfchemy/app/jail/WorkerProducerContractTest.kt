package com.pdfchemy.app.jail

import com.google.gson.Gson
import com.pdfchemy.app.logic.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

        val serializedSupported = gson.toJson(supportedAnalysis)
        val contractSupported = WorkerResponseValidator.validate("IMAGE_ANALYZE", serializedSupported)
        assertTrue(contractSupported is ImageAnalysisContract)
        val verifiedSupported = (contractSupported as ImageAnalysisContract).analysis
        assertEquals(1600, verifiedSupported.width)
        assertEquals(1200, verifiedSupported.height)
        assertEquals(PerceivedQualityLoss.MINIMAL, verifiedSupported.qualityLoss)
        assertEquals(ImageValidationStatus.ALLOWED, verifiedSupported.validationStatus)
        assertTrue(verifiedSupported.isSupported)

        // 2. Warning already compressed instance (supported = true, positive dimensions)
        val warningAnalysis = ImageAnalysis(
            width = 1920,
            height = 1080,
            originalSizeBytes = 120_000L,
            mimeType = "image/jpeg",
            formatName = "JPEG",
            isSupported = true,
            validationStatus = ImageValidationStatus.WARNING_ALREADY_COMPRESSED,
            validationMessage = "Image is already highly compressed.",
            alternativeSuggestion = null,
            estimatedCompressedBytes = 115_000L,
            estimatedSavingsPercent = 4,
            qualityLoss = PerceivedQualityLoss.NEGLIGIBLE
        )
        val serializedWarning = gson.toJson(warningAnalysis)
        val contractWarning = WorkerResponseValidator.validate("IMAGE_ANALYZE", serializedWarning)
        assertTrue(contractWarning is ImageAnalysisContract)
        val verifiedWarning = (contractWarning as ImageAnalysisContract).analysis
        assertEquals(1920, verifiedWarning.width)
        assertEquals(1080, verifiedWarning.height)
        assertEquals(ImageValidationStatus.WARNING_ALREADY_COMPRESSED, verifiedWarning.validationStatus)
        assertTrue(verifiedWarning.isSupported)

        // 3. Unsupported format ImageAnalysis producer instance (supported = false, 0x0 dimensions)
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

        // 4. Corrupt image analysis (supported = false, 0x0 dimensions)
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
        assertFalse(verifiedCorrupt.isSupported)

        // 5. Denied too small instance (supported = false, positive dimensions)
        val tooSmallAnalysis = ImageAnalysis(
            width = 64,
            height = 64,
            originalSizeBytes = 800L,
            mimeType = "image/png",
            formatName = "PNG",
            isSupported = false,
            validationStatus = ImageValidationStatus.DENIED_TOO_SMALL,
            validationMessage = "Image is too small to compress effectively.",
            alternativeSuggestion = null,
            estimatedCompressedBytes = 800L,
            estimatedSavingsPercent = 0,
            qualityLoss = PerceivedQualityLoss.NEGLIGIBLE
        )
        val serializedTooSmall = gson.toJson(tooSmallAnalysis)
        val contractTooSmall = WorkerResponseValidator.validate("IMAGE_ANALYZE", serializedTooSmall)
        assertTrue(contractTooSmall is ImageAnalysisContract)
        val verifiedTooSmall = (contractTooSmall as ImageAnalysisContract).analysis
        assertEquals(64, verifiedTooSmall.width)
        assertEquals(64, verifiedTooSmall.height)
        assertEquals(ImageValidationStatus.DENIED_TOO_SMALL, verifiedTooSmall.validationStatus)
        assertFalse(verifiedTooSmall.isSupported)
    }
}
