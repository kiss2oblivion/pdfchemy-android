package com.pdfchemy.app.logic

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri

enum class ImageOutputFormat(val displayName: String, val extension: String, val mimeType: String) {
    ORIGINAL("Original", "jpg", "image/jpeg"),
    JPEG("JPEG", "jpg", "image/jpeg"),
    WEBP("WebP", "webp", "image/webp"),
    PNG("PNG", "png", "image/png")
}

enum class ImageValidationStatus {
    ALLOWED,
    WARNING_ALREADY_COMPRESSED,
    DENIED_UNSUPPORTED_FORMAT,
    DENIED_CORRUPT,
    DENIED_TOO_SMALL
}

enum class PerceivedQualityLoss(val level: String, val stars: Int) {
    NEGLIGIBLE("Negligible (Crisp & Clear)", 5),
    MINIMAL("Minimal (Great for Mobile)", 4),
    MODERATE("Moderate (Ideal for Web)", 3),
    SIGNIFICANT("Significant (Compact Storage)", 2)
}

data class ImageAnalysis(
    val width: Int,
    val height: Int,
    val originalSizeBytes: Long,
    val mimeType: String,
    val formatName: String,
    val isSupported: Boolean,
    val validationStatus: ImageValidationStatus,
    val validationMessage: String?,
    val alternativeSuggestion: String?,
    val estimatedCompressedBytes: Long,
    val estimatedSavingsPercent: Int,
    val qualityLoss: PerceivedQualityLoss
)

data class ImageCompressionResult(
    val success: Boolean,
    val originalSize: Long,
    val compressedSize: Long,
    val width: Int,
    val height: Int,
    val format: String,
    val error: String? = null
) {
    val bytesSaved: Long get() = (originalSize - compressedSize).coerceAtLeast(0L)
    val percentSaved: Int get() = if (originalSize > 0) (((originalSize - compressedSize).toDouble() / originalSize) * 100).toInt().coerceIn(0, 100) else 0
}

data class BatchImageCompressionResult(
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val totalOriginalBytes: Long,
    val totalCompressedBytes: Long,
    val outputUris: List<Uri>,
    val errors: List<String> = emptyList()
) {
    val totalBytesSaved: Long get() = (totalOriginalBytes - totalCompressedBytes).coerceAtLeast(0L)
    val percentSaved: Int get() = if (totalOriginalBytes > 0) (((totalOriginalBytes - totalCompressedBytes).toDouble() / totalOriginalBytes) * 100).toInt().coerceIn(0, 100) else 0
}

