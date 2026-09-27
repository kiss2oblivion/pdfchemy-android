package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
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
        val newInfo = DocumentMetadataInfo(
            title = newMetadata.title,
            author = newMetadata.author,
            subject = newMetadata.subject,
            keywords = newMetadata.keywords,
            creator = newMetadata.creator,
            producer = newMetadata.producer
        )
        val res = PdfMetadataEngine.writeOrSanitizeMetadata(context, sourceUri, destUri, newInfo, false)
        if (res.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Unknown error writing metadata"))
        }
    }

    suspend fun clearMetadata(
        context: Context,
        sourceUri: Uri,
        destUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val res = PdfMetadataEngine.writeOrSanitizeMetadata(context, sourceUri, destUri, null, true)
        if (res.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("Unknown error clearing metadata"))
        }
    }

    suspend fun clearMetadataOverwrite(
        context: Context,
        sourceUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val tempDest = java.io.File.createTempFile("temp_stripped", ".pdf", context.cacheDir)
        try {
            val destUri = Uri.fromFile(tempDest)
            val res = PdfMetadataEngine.writeOrSanitizeMetadata(context, sourceUri, destUri, null, true)
            if (res.isSuccess) {
                context.contentResolver.openOutputStream(sourceUri, "wt")?.use { outputStream ->
                    tempDest.inputStream().use { it.copyTo(outputStream) }
                }
                Result.success(Unit)
            } else {
                Result.failure(res.exceptionOrNull() ?: Exception("Unknown error clearing metadata overwrite"))
            }
        } finally {
            tempDest.delete()
        }
    }
}
