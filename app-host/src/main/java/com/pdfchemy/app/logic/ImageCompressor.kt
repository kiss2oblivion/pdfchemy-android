package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageCompressor {
    fun resolveOutputFormat(requested: ImageOutputFormat, sourceMime: String): ImageOutputFormat =
        if (requested != ImageOutputFormat.ORIGINAL) requested else when (sourceMime.lowercase(java.util.Locale.ROOT)) {
            "image/png" -> ImageOutputFormat.PNG
            "image/webp" -> ImageOutputFormat.WEBP
            else -> ImageOutputFormat.JPEG
        }

    suspend fun analyzeImage(context: Context, uri: Uri, quality: Int = 65, targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL, targetBytes: Long? = null): ImageAnalysis = withContext(Dispatchers.IO) {
        val contract = JailEngineBridge.callTyped<ImageAnalysisContract>(
            context, "IMAGE_ANALYZE", uri, null,
            mapOf("quality" to quality, "format" to targetFormat.name, "targetBytes" to targetBytes)
        )
        contract.analysis
    }

    suspend fun compressImage(context: Context, sourceUri: Uri, destUri: Uri, quality: Int = 75, targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL, maxDimension: Int = 0, stripExif: Boolean = true): ImageCompressionResult {
        val contract = JailEngineBridge.callTyped<ImageCompressionResultContract>(
            context, "IMAGE_COMPRESS", sourceUri, destUri,
            mapOf("quality" to quality, "format" to targetFormat.name, "maxDimension" to maxDimension, "stripExif" to stripExif)
        )
        return contract.result
    }

    suspend fun compressToTargetSize(context: Context, sourceUri: Uri, destUri: Uri, targetSizeBytes: Long, targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL, stripExif: Boolean = true): ImageCompressionResult {
        val contract = JailEngineBridge.callTyped<ImageCompressionResultContract>(
            context, "IMAGE_TARGET", sourceUri, destUri,
            mapOf("targetBytes" to targetSizeBytes, "format" to targetFormat.name, "stripExif" to stripExif)
        )
        return contract.result
    }

    data class ImageBounds(val outWidth: Int, val outHeight: Int)

    suspend fun decodeImageBounds(context: Context, uri: Uri): ImageBounds? = withContext(Dispatchers.IO) {
        val contract = JailEngineBridge.callTyped<ImageBoundsContract>(context, "IMAGE_BOUNDS", uri, null)
        ImageBounds(contract.outWidth, contract.outHeight)
    }

    fun getUriFileSize(context: Context, uri: Uri): Long = com.pdfchemy.app.utils.FileUtils.getFileSize(context, uri)

    fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width.toLong() / sample * (height / sample) > SecurityLimits.MAX_RENDER_PIXELS || maxOf(width, height) / sample > (if (maxDimension > 0) minOf(maxDimension, SecurityLimits.MAX_RENDER_DIMENSION) else SecurityLimits.MAX_RENDER_DIMENSION)) sample *= 2
        return sample
    }
}
