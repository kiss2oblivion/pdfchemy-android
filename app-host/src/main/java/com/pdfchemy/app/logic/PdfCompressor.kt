// =================================================================================================
// [FEATURE: PDF Compressor & Image Re-encoding Engine] (FEATURES_REGISTRY Android  1)
// Core document size reduction, DCT/JPEG & Flate optimization, grayscale, and target MB sizing.
// =================================================================================================

package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.security.FreshOutputRegistry
import com.pdfchemy.app.security.SecurityLimits
import com.pdfchemy.app.security.BoundedOutputStream
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

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
        var stagedPdf: com.pdfchemy.app.jail.StagedPdf? = null
        var inputLease: java.io.Closeable? = null
        val temporaryFiles = mutableListOf<java.io.File>()
        val targets = listOf(destUri)
        val publicationOwner = Any()
        var claimedOutput = false
        try {
            com.pdfchemy.app.security.SourcePreservationPolicy.requireSeparateDestination(context, sourceUri, destUri)
            FreshOutputRegistry.claimKnown(targets, publicationOwner)
            claimedOutput = true
            FreshOutputRegistry.requireSafeSafTargets(targets, publicationOwner)
            val stagedLease = DocumentStager.stageDocumentCancellableWithLease(context, sourceUri)
            stagedPdf = stagedLease.document
            inputLease = stagedLease.lease
            val cacheDir = context.cacheDir
            val tempFile1 = java.io.File.createTempFile("temp_compress_", ".pdf", cacheDir).also(temporaryFiles::add)
            val tempUri1 = Uri.fromFile(tempFile1)

            val pass1Result = compressSinglePass(context, stagedPdf.uri, tempUri1, quality, useGrayscale, useLossless, stripMetadata)
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

                    val tempFile2 = java.io.File.createTempFile("temp_compress_", ".pdf", cacheDir).also(temporaryFiles::add)
                    val tempUri2 = Uri.fromFile(tempFile2)

                    val pass2Result = compressSinglePass(context, stagedPdf.uri, tempUri2, tighterQuality, useGrayscale, useLossless, stripMetadata)
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

            // Claim before processing, but start the bounded collector's deadline only
            // when the selected compression pass is ready for final publication.
            HostOutputTransaction(context, targets, previousPublicationOwner = publicationOwner).use { transaction ->
                bestTempFile.inputStream().use { input ->
                    BoundedOutputStream(
                        ParcelFileDescriptor.AutoCloseOutputStream(transaction.workerDescriptors.single().dup()),
                        SecurityLimits.MAX_OUTPUT_BYTES
                    ).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                transaction.validateAndSnapshot("{\"success\":true}")
                transaction.commit()
            }
            Result.success(bestReport.copy(targetMissed = targetMb != null && bestTempFile.length() > (targetMb * 1024 * 1024).toLong()))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            AppLogger.e("PdfCompressor: Compression failed", e)
            Result.failure(e)
        } finally {
            if (claimedOutput) FreshOutputRegistry.abandon(context, targets, publicationOwner)
            temporaryFiles.forEach { it.delete() }
            stagedPdf?.takeIf { it.uri != sourceUri }?.let(DocumentStager::release)
            inputLease?.close()
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

            val contract = PdfGateway.executeEngineTyped<CompressContract>(context, "COMPRESS", sourceUri, destUri,
                org.json.JSONObject().put("targetDpi", targetDpi).put("quality", quality)
                    .put("useGrayscale", useGrayscale).put("useLossless", useLossless).put("stripMetadata", stripMetadata).toString())

            val report = CompressionReport(
                originalSize = fileSize,
                imagesProcessed = contract.imagesProcessed,
                hasSignatures = contract.hasSignatures,
                targetMissed = false
            )

            Result.success(report)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
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
            val analysis = PdfGateway.analyzePdf(context, uri)

            val scenario = when (analysis.scenario) {
                "SIGNED_OFFICIAL" -> PdfScenario.SIGNED_OFFICIAL
                "TEXT_VECTOR" -> PdfScenario.TEXT_VECTOR
                "SCANNED_IMAGE_HEAVY" -> PdfScenario.SCANNED_IMAGE_HEAVY
                else -> PdfScenario.MIXED
            }

            Result.success(PdfAnalysis(
                pageCount = analysis.pageCount,
                imageCount = analysis.imageCount,
                hasSignatures = analysis.hasSignatures,
                scenario = scenario,
                recommendedQuality = analysis.recommendedQuality,
                recommendationReason = analysis.recommendationReason
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
