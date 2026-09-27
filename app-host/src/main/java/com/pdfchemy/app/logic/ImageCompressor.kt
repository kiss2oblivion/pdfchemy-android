package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

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

object ImageCompressor {

    private val ALLOWED_MIME_TYPES = setOf(
        "image/jpeg", "image/jpg", "image/png", "image/webp", "image/bmp", "image/x-ms-bmp", "image/heic", "image/heif"
    )

    private val DENIED_VECTOR_OR_ANIMATED = setOf(
        "image/svg+xml", "image/gif", "application/pdf"
    )

    /**
     * Inspects and analyzes a graphic to evaluate compressibility, detect validation issues,
     * calculate estimated size reduction, and assess perceived quality loss.
     */
    fun analyzeImage(
        context: Context,
        uri: Uri,
        quality: Int = 65,
        targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL,
        targetBytes: Long? = null
    ): ImageAnalysis {
        val originalSize = getUriFileSize(context, uri)
        val rawMime = (context.contentResolver.getType(uri) ?: "").lowercase(Locale.ROOT)
        val path = uri.path?.lowercase(Locale.ROOT) ?: ""

        val isVectorOrGif = DENIED_VECTOR_OR_ANIMATED.any { rawMime.contains(it) } || path.endsWith(".svg") || path.endsWith(".gif")
        if (isVectorOrGif) {
            val formatStr = if (rawMime.contains("svg") || path.endsWith(".svg")) "SVG" else "GIF"
            return ImageAnalysis(
                width = 0,
                height = 0,
                originalSizeBytes = originalSize,
                mimeType = rawMime,
                formatName = formatStr,
                isSupported = false,
                validationStatus = ImageValidationStatus.DENIED_UNSUPPORTED_FORMAT,
                validationMessage = "Vector or animated formats ($formatStr) cannot be raster-compressed.",
                alternativeSuggestion = "Tip: Use 'Images to PDF' to convert photos or share the graphic directly.",
                estimatedCompressedBytes = originalSize,
                estimatedSavingsPercent = 0,
                qualityLoss = PerceivedQualityLoss.NEGLIGIBLE
            )
        }

        val bounds = decodeImageBounds(context, uri)
        if (bounds == null || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return ImageAnalysis(
                width = 0,
                height = 0,
                originalSizeBytes = originalSize,
                mimeType = rawMime,
                formatName = "Corrupt/Unknown",
                isSupported = false,
                validationStatus = ImageValidationStatus.DENIED_CORRUPT,
                validationMessage = "The selected file is corrupted or not a valid image.",
                alternativeSuggestion = "Please choose a valid JPG, PNG, WebP, or HEIC photo.",
                estimatedCompressedBytes = originalSize,
                estimatedSavingsPercent = 0,
                qualityLoss = PerceivedQualityLoss.NEGLIGIBLE
            )
        }

        val width = bounds.outWidth
        val height = bounds.outHeight

        // Check if image is already too small (< 40 KB)
        if (originalSize in 1..40_000L) {
            return ImageAnalysis(
                width = width,
                height = height,
                originalSizeBytes = originalSize,
                mimeType = rawMime.ifEmpty { "image/jpeg" },
                formatName = resolveFormatDisplayName(rawMime, path),
                isSupported = false,
                validationStatus = ImageValidationStatus.DENIED_TOO_SMALL,
                validationMessage = "File is already ultra-compact (${originalSize / 1024} KB). Compressing will not save space.",
                alternativeSuggestion = "This image is ready to send as-is.",
                estimatedCompressedBytes = originalSize,
                estimatedSavingsPercent = 0,
                qualityLoss = PerceivedQualityLoss.NEGLIGIBLE
            )
        }

        // Detect if already heavily compressed (e.g. huge dimensions but tiny bytes < 150KB)
        val pixelCount = width.toLong() * height.toLong()
        val isAlreadyOptimized = (pixelCount > 2_000_000 && originalSize < 150_000)

        val validationStatus = if (isAlreadyOptimized) {
            ImageValidationStatus.WARNING_ALREADY_COMPRESSED
        } else {
            ImageValidationStatus.ALLOWED
        }

        val validationMsg = if (isAlreadyOptimized) {
            "This image is already compressed. Further compression may cause visual softness."
        } else null

        // Calculate Estimation
        val estimatedBytes: Long
        val qualityLoss: PerceivedQualityLoss

        if (targetBytes != null && targetBytes > 0) {
            estimatedBytes = targetBytes.coerceAtMost(originalSize)
            val ratio = estimatedBytes.toDouble() / originalSize
            qualityLoss = when {
                ratio >= 0.70 -> PerceivedQualityLoss.NEGLIGIBLE
                ratio >= 0.45 -> PerceivedQualityLoss.MINIMAL
                ratio >= 0.20 -> PerceivedQualityLoss.MODERATE
                else -> PerceivedQualityLoss.SIGNIFICANT
            }
        } else {
            val qFactor = quality / 100.0
            val formatFactor = when (targetFormat) {
                ImageOutputFormat.WEBP -> 0.70
                ImageOutputFormat.PNG -> 0.95
                else -> 0.85
            }
            val rawEstimate = (originalSize * qFactor * formatFactor).toLong().coerceIn(15_000L, originalSize)
            estimatedBytes = rawEstimate
            qualityLoss = when {
                quality >= 80 -> PerceivedQualityLoss.NEGLIGIBLE
                quality >= 60 -> PerceivedQualityLoss.MINIMAL
                quality >= 35 -> PerceivedQualityLoss.MODERATE
                else -> PerceivedQualityLoss.SIGNIFICANT
            }
        }

        val savingsPct = if (originalSize > 0) {
            (((originalSize - estimatedBytes).toDouble() / originalSize) * 100).toInt().coerceIn(0, 95)
        } else 0

        return ImageAnalysis(
            width = width,
            height = height,
            originalSizeBytes = originalSize,
            mimeType = rawMime.ifEmpty { "image/jpeg" },
            formatName = resolveFormatDisplayName(rawMime, path),
            isSupported = true,
            validationStatus = validationStatus,
            validationMessage = validationMsg,
            alternativeSuggestion = null,
            estimatedCompressedBytes = estimatedBytes,
            estimatedSavingsPercent = savingsPct,
            qualityLoss = qualityLoss
        )
    }

    private fun resolveFormatDisplayName(mime: String, path: String): String {
        return when {
            mime.contains("png") || path.endsWith(".png") -> "PNG"
            mime.contains("webp") || path.endsWith(".webp") -> "WebP"
            mime.contains("heic") || mime.contains("heif") || path.endsWith(".heic") -> "HEIC"
            mime.contains("bmp") || path.endsWith(".bmp") -> "BMP"
            else -> "JPEG"
        }
    }

    /**
     * Compresses a single image with explicit quality and format parameters.
     */
    suspend fun compressImage(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        quality: Int = 75,
        targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL,
        maxDimension: Int = 0,
        stripExif: Boolean = true
    ): ImageCompressionResult = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            val originalSize = getUriFileSize(context, sourceUri)
            val orientation = getExifOrientation(context, sourceUri)

            val bounds = decodeImageBounds(context, sourceUri)
                ?: return@withContext ImageCompressionResult(false, originalSize, 0, 0, 0, "", "Could not read image bounds")

            val originalWidth = bounds.outWidth
            val originalHeight = bounds.outHeight

            val sampleSize = calculateInSampleSize(originalWidth, originalHeight, maxDimension)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            bitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            }

            if (bitmap == null) {
                return@withContext ImageCompressionResult(false, originalSize, 0, 0, 0, "", "Failed to decode bitmap")
            }

            if (orientation != 0) {
                val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }

            if (maxDimension > 0 && (bitmap.width > maxDimension || bitmap.height > maxDimension)) {
                val currentWidth = bitmap.width
                val currentHeight = bitmap.height
                val ratio = currentWidth.toFloat() / currentHeight.toFloat()
                val targetW: Int
                val targetH: Int
                if (currentWidth >= currentHeight) {
                    targetW = maxDimension
                    targetH = (maxDimension / ratio).toInt().coerceAtLeast(1)
                } else {
                    targetH = maxDimension
                    targetW = (maxDimension * ratio).toInt().coerceAtLeast(1)
                }
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (scaled != bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            val finalWidth = bitmap.width
            val finalHeight = bitmap.height

            val compressFormat = resolveCompressFormat(context, sourceUri, targetFormat)
            val compressQuality = quality.coerceIn(1, 100)

            val outputStream: OutputStream = context.contentResolver.openOutputStream(destUri)
                ?: throw IllegalStateException("Cannot open output stream for destination URI")

            outputStream.use { out ->
                bitmap.compress(compressFormat, compressQuality, out)
                out.flush()
            }

            val compressedSize = getUriFileSize(context, destUri)

            ImageCompressionResult(
                success = true,
                originalSize = originalSize,
                compressedSize = compressedSize,
                width = finalWidth,
                height = finalHeight,
                format = compressFormat.name
            )
        } catch (e: Exception) {
            AppLogger.e("ImageCompressor failed to compress image", e)
            ImageCompressionResult(
                success = false,
                originalSize = 0L,
                compressedSize = 0L,
                width = 0,
                height = 0,
                format = "",
                error = e.message ?: "Unknown compression error"
            )
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Compresses an image to fit under a specific target byte size (e.g. 2MB, 5MB, 10MB, or Custom).
     * Uses an adaptive binary search over quality + resolution scaling down if necessary.
     */
    suspend fun compressToTargetSize(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        targetSizeBytes: Long,
        targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL,
        stripExif: Boolean = true
    ): ImageCompressionResult = withContext(Dispatchers.IO) {
        var currentBitmap: Bitmap? = null
        try {
            val originalSize = getUriFileSize(context, sourceUri)

            // If original already fits under target size, do a light optimization (90% quality)
            if (originalSize <= targetSizeBytes) {
                return@withContext compressImage(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    quality = 90,
                    targetFormat = targetFormat,
                    maxDimension = 0,
                    stripExif = stripExif
                )
            }

            val orientation = getExifOrientation(context, sourceUri)
            val bounds = decodeImageBounds(context, sourceUri)
                ?: return@withContext ImageCompressionResult(false, originalSize, 0, 0, 0, "", "Could not decode bounds")

            var maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            val compressFormat = resolveCompressFormat(context, sourceUri, targetFormat)

            // Binary search on quality
            var lowQ = 10
            var highQ = 92
            var bestQuality = 60
            var bestBytes = ByteArray(0)

            // Adaptive loop with max 3 downscale reductions if quality alone is insufficient
            var downscaleFactor = 1.0f
            val bos = ByteArrayOutputStream(64 * 1024)

            for (attempt in 0..2) {
                val targetDim = if (downscaleFactor < 1.0f) (maxDim * downscaleFactor).toInt() else 0
                val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetDim)
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                currentBitmap?.recycle()
                currentBitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, decodeOptions)
                }

                if (currentBitmap == null) break

                if (orientation != 0) {
                    val matrix = Matrix().apply { postRotate(orientation.toFloat()) }
                    val rotated = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, matrix, true)
                    if (rotated != currentBitmap) {
                        currentBitmap.recycle()
                        currentBitmap = rotated
                    }
                }

                if (targetDim > 0 && (currentBitmap.width > targetDim || currentBitmap.height > targetDim)) {
                    val ratio = currentBitmap.width.toFloat() / currentBitmap.height.toFloat()
                    val targetW = if (currentBitmap.width >= currentBitmap.height) targetDim else (targetDim * ratio).toInt()
                    val targetH = if (currentBitmap.height > currentBitmap.width) targetDim else (targetDim / ratio).toInt()
                    val scaled = Bitmap.createScaledBitmap(currentBitmap, targetW.coerceAtLeast(1), targetH.coerceAtLeast(1), true)
                    if (scaled != currentBitmap) {
                        currentBitmap.recycle()
                        currentBitmap = scaled
                    }
                }

                // Perform binary search on quality
                lowQ = 10
                highQ = 90
                var iterationBestBytes: ByteArray? = null
                while (lowQ <= highQ) {
                    val midQ = (lowQ + highQ) / 2
                    bos.reset()
                    currentBitmap.compress(compressFormat, midQ, bos)
                    val size = bos.size().toLong()

                    if (size <= targetSizeBytes) {
                        bestQuality = midQ
                        iterationBestBytes = bos.toByteArray()
                        lowQ = midQ + 1 // try higher quality
                    } else {
                        highQ = midQ - 1 // try lower quality
                    }
                }

                if (iterationBestBytes != null) {
                    bestBytes = iterationBestBytes
                    break // Met target size!
                }

                // If even minimum quality didn't fit, scale down resolution for next iteration
                downscaleFactor *= 0.70f
            }

            if (bestBytes.isEmpty() && currentBitmap != null) {
                // Fallback: compress at 10% quality
                bos.reset()
                currentBitmap.compress(compressFormat, 10, bos)
                bestBytes = bos.toByteArray()
            }

            val finalWidth = currentBitmap?.width ?: bounds.outWidth
            val finalHeight = currentBitmap?.height ?: bounds.outHeight

            val outputStream: OutputStream = context.contentResolver.openOutputStream(destUri)
                ?: throw IllegalStateException("Cannot open output stream for destination URI")

            outputStream.use { out ->
                out.write(bestBytes)
                out.flush()
            }

            val compressedSize = getUriFileSize(context, destUri)

            ImageCompressionResult(
                success = true,
                originalSize = originalSize,
                compressedSize = compressedSize,
                width = finalWidth,
                height = finalHeight,
                format = compressFormat.name
            )
        } catch (e: Exception) {
            AppLogger.e("Failed to compress to target size", e)
            ImageCompressionResult(
                success = false,
                originalSize = 0L,
                compressedSize = 0L,
                width = 0,
                height = 0,
                format = "",
                error = e.message ?: "Target size compression error"
            )
        } finally {
            currentBitmap?.recycle()
        }
    }

    fun decodeImageBounds(context: Context, uri: Uri): BitmapFactory.Options? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                options
            }
        } catch (e: Exception) {
            null
        }
    }

    fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        if (maxDimension <= 0) return 1

        val largestDim = maxOf(width, height)
        if (largestDim > maxDimension) {
            val halfDim = largestDim / 2
            while ((halfDim / inSampleSize) >= maxDimension) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun resolveCompressFormat(context: Context, sourceUri: Uri, targetFormat: ImageOutputFormat): Bitmap.CompressFormat {
        return when (targetFormat) {
            ImageOutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageOutputFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageOutputFormat.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
            ImageOutputFormat.ORIGINAL -> {
                val mime = context.contentResolver.getType(sourceUri)?.lowercase(Locale.ROOT) ?: ""
                when {
                    mime.contains("png") -> Bitmap.CompressFormat.PNG
                    mime.contains("webp") -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                    }
                    else -> Bitmap.CompressFormat.JPEG
                }
            }
        }
    }

    fun getUriFileSize(context: Context, uri: Uri): Long {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) java.io.File(path).length() else 0L
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    it.length
                } ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
