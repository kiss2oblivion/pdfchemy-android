package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.security.SourcePreservationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PdfMetadata(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
    val producer: String = ""
)

class PdfMetadataManager {

    suspend fun getMetadata(context: Context, uri: Uri): Result<PdfMetadata> = withContext(Dispatchers.IO) {
        val res = PdfMetadataEngine.readMetadata(context, uri)
        if (res.isSuccess) {
            val docInfo = res.getOrThrow()
            Result.success(
                PdfMetadata(
                    title = docInfo.title,
                    author = docInfo.author,
                    subject = docInfo.subject,
                    keywords = docInfo.keywords,
                    creator = docInfo.creator,
                    producer = docInfo.producer
                )
            )
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Unknown error reading metadata"))
        }
    }

    suspend fun updateMetadata(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        newMetadata: PdfMetadata
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SourcePreservationPolicy.requireSeparateDestination(context, sourceUri, destUri)
            val newInfo = DocumentMetadataInfo(
                title = newMetadata.title,
                author = newMetadata.author,
                subject = newMetadata.subject,
                keywords = newMetadata.keywords,
                creator = newMetadata.creator,
                producer = newMetadata.producer
            )
            val res = PdfMetadataEngine.writeOrSanitizeMetadata(
                context = context,
                sourcePdfUri = sourceUri,
                destPdfUri = destUri,
                newMetadata = newInfo,
                wipeAllMetadata = false,
                recordHistory = false
            )
            if (res.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(res.exceptionOrNull() ?: Exception("Unknown error writing metadata"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun clearMetadata(
        context: Context,
        sourceUri: Uri,
        destUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SourcePreservationPolicy.requireSeparateDestination(context, sourceUri, destUri)
            val res = PdfMetadataEngine.writeOrSanitizeMetadata(
                context = context,
                sourcePdfUri = sourceUri,
                destPdfUri = destUri,
                newMetadata = null,
                wipeAllMetadata = true,
                recordHistory = false
            )
            if (res.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(res.exceptionOrNull() ?: Exception("Unknown error clearing metadata"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
