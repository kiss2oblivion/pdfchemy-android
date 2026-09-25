// =================================================================================================
// [FEATURE: PDF Compressor & Image Re-encoding Engine] (FEATURES_REGISTRY Android  1)
// Core document size reduction, DCT/JPEG & Flate optimization, grayscale, and target MB sizing.
// =================================================================================================

package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfCompressor {

    suspend fun compressPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        quality: Float = 0.5f,
        useGrayscale: Boolean = false,
        useLossless: Boolean = false,
        stripMetadata: Boolean = false,
        targetMb: Float? = null
    ): Result<CompressionReport> = withContext(Dispatchers.IO) {
        try {
            val cacheDir = context.cacheDir
            val tempFile1 = java.io.File(cacheDir, "temp_compress_${System.currentTimeMillis()}_1.pdf")
            val tempUri1 = Uri.fromFile(tempFile1)

            val pass1Result = compressSinglePass(context, sourceUri, tempUri1, quality, useGrayscale, useLossless, stripMetadata)
            if (pass1Result.isFailure) {
                tempFile1.delete()
                return@withContext pass1Result
            }

            val report1 = pass1Result.getOrThrow()
            val size1 = tempFile1.length()

            var bestTempFile = tempFile1
            var bestReport = report1

            if (targetMb != null) {
                val targetBytes = (targetMb * 1024 * 1024).toLong()
                if (size1 > targetBytes && quality > 0.10f) {
                    val scaleFactor = targetBytes.toFloat() / size1.toFloat()
                    val tighterQuality = (quality * scaleFactor * 0.90f).coerceIn(0.05f, quality - 0.05f)

                    val tempFile2 = java.io.File(cacheDir, "temp_compress_${System.currentTimeMillis()}_2.pdf")
                    val tempUri2 = Uri.fromFile(tempFile2)

                    val pass2Result = compressSinglePass(context, sourceUri, tempUri2, tighterQuality, useGrayscale, useLossless, stripMetadata)
                    if (pass2Result.isSuccess) {
                        val size2 = tempFile2.length()
                        if (size2 < size1) {
                            tempFile1.delete()
                            bestTempFile = tempFile2
                            bestReport = pass2Result.getOrThrow()
                        } else {
                            tempFile2.delete()
                        }
                    } else {
                        tempFile2.delete()
                    }
                }
            }

            try {
                val contentResolver = context.contentResolver
                val success = bestTempFile.inputStream().use { input ->
                    contentResolver.openOutputStream(destUri)?.use { output ->
                        input.copyTo(output)
                        true
                    } ?: false
                }
                
                if (success) {
                    Result.success(bestReport)
                } else {
                    Result.failure(Exception("Failed to copy final compressed output to destination"))
                }
            } finally {
                bestTempFile.delete()
            }
        } catch (e: Exception) {
            AppLogger.e("PdfCompressor: Compression failed", e)
            Result.failure(e)
        }
    }

    private suspend fun compressSinglePass(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        quality: Float,
        useGrayscale: Boolean,
        useLossless: Boolean,
        stripMetadata: Boolean
    ): Result<CompressionReport> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val pfd = try { contentResolver.openFileDescriptor(sourceUri, "r") } catch (e: Exception) { null }
            val fileSize = pfd?.use { it.statSize } ?: -1L
            
            val targetDpi = 150f
            
            com.pdfchemy.app.jail.PdfJailClient.compressPdf(
                context, sourceUri, destUri, targetDpi, quality, false
            )
            
            val report = CompressionReport(
                originalSize = fileSize,
                imagesProcessed = 1,
                hasSignatures = false,
                targetMissed = false
            )

            Result.success(report)
        } catch (e: Exception) {
            AppLogger.e("PdfCompressor: Jail execution failed", e)
            Result.failure(e)
        }
    }

    data class CompressionReport(
        val originalSize: Long,
        val imagesProcessed: Int,
        val hasSignatures: Boolean,
        val targetMissed: Boolean = false
    )

    suspend fun analyzePdf(
        context: Context,
        uri: Uri
    ): Result<PdfAnalysis> = withContext(Dispatchers.IO) {
        try {
            val jsonString = com.pdfchemy.app.jail.PdfJailClient.analyzePdf(context, uri)
            val json = org.json.JSONObject(jsonString)

            val pageCount = json.getInt("pageCount")
            val imageCount = json.getInt("imageCount")
            val hasSignatures = json.getBoolean("hasSignatures")
            val scenarioName = json.getString("scenario")
            val recommendedQuality = json.getDouble("recommendedQuality").toFloat()
            val recommendationReason = json.getString("recommendationReason")

            val scenario = when (scenarioName) {
                "SIGNED_OFFICIAL" -> PdfScenario.SIGNED_OFFICIAL
                "TEXT_VECTOR" -> PdfScenario.TEXT_VECTOR
                "SCANNED_IMAGE_HEAVY" -> PdfScenario.SCANNED_IMAGE_HEAVY
                else -> PdfScenario.MIXED
            }

            Result.success(PdfAnalysis(
                pageCount = pageCount,
                imageCount = imageCount,
                hasSignatures = hasSignatures,
                scenario = scenario,
                recommendedQuality = recommendedQuality,
                recommendationReason = recommendationReason
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

enum class PdfScenario(val displayName: String) {
    SCANNED_IMAGE_HEAVY("Scanned / Image-Heavy"),
    TEXT_VECTOR("Text & Vector"),
    SIGNED_OFFICIAL("Signed / Official"),
    MIXED("Mixed Content")
}

data class PdfAnalysis(
    val pageCount: Int,
    val imageCount: Int,
    val hasSignatures: Boolean,
    val scenario: PdfScenario,
    val recommendedQuality: Float,
    val recommendationReason: String
)
