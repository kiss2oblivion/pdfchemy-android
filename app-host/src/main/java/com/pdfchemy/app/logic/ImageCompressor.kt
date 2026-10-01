package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import com.pdfchemy.app.security.PixelWire
import com.pdfchemy.app.security.SecurityLimits
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ImageCompressor {
    suspend fun analyzeImage(context: Context, uri: Uri, quality: Int = 65, targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL, targetBytes: Long? = null): ImageAnalysis = withContext(Dispatchers.IO) {
        Gson().fromJson(JailEngineBridge.call(context, "IMAGE_ANALYZE", uri, null, mapOf("quality" to quality, "format" to targetFormat.name, "targetBytes" to targetBytes)), ImageAnalysis::class.java)
    }
    suspend fun compressImage(context: Context, sourceUri: Uri, destUri: Uri, quality: Int = 75, targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL, maxDimension: Int = 0, stripExif: Boolean = true): ImageCompressionResult =
        Gson().fromJson(JailEngineBridge.call(context, "IMAGE_COMPRESS", sourceUri, destUri, mapOf("quality" to quality, "format" to targetFormat.name, "maxDimension" to maxDimension, "stripExif" to stripExif)), ImageCompressionResult::class.java)
    suspend fun compressToTargetSize(context: Context, sourceUri: Uri, destUri: Uri, targetSizeBytes: Long, targetFormat: ImageOutputFormat = ImageOutputFormat.ORIGINAL, stripExif: Boolean = true): ImageCompressionResult =
        Gson().fromJson(JailEngineBridge.call(context, "IMAGE_TARGET", sourceUri, destUri, mapOf("targetBytes" to targetSizeBytes, "format" to targetFormat.name, "stripExif" to stripExif)), ImageCompressionResult::class.java)
    data class ImageBounds(val outWidth: Int, val outHeight: Int)
    suspend fun decodeImageBounds(context: Context, uri: Uri): ImageBounds? = withContext(Dispatchers.IO) {
        Gson().fromJson(JailEngineBridge.call(context, "IMAGE_BOUNDS", uri, null), ImageBounds::class.java)
    }
    fun getUriFileSize(context: Context, uri: Uri): Long = com.pdfchemy.app.utils.FileUtils.getFileSize(context, uri)
    fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width.toLong() / sample * (height / sample) > SecurityLimits.MAX_RENDER_PIXELS || maxOf(width, height) / sample > (if (maxDimension > 0) minOf(maxDimension, SecurityLimits.MAX_RENDER_DIMENSION) else SecurityLimits.MAX_RENDER_DIMENSION)) sample *= 2
        return sample
    }
}
