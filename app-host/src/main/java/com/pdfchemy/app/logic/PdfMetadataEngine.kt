package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.security.SourcePreservationPolicy
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class DocumentMetadataInfo(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
    val producer: String = "",
    val creationDate: String = "",
    val modificationDate: String = "",
    val pageCount: Int = 0,
    val hasXmpMetadata: Boolean = false,
    val isEncrypted: Boolean = false
)

object PdfMetadataEngine {
    suspend fun readMetadata(context: Context, pdfUri: Uri): Result<DocumentMetadataInfo> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<MetadataReadContract>(context, "METADATA_READ", pdfUri, null, "{}")
            val result = DocumentMetadataInfo(
                title = contract.title,
                author = contract.author,
                subject = contract.subject,
                keywords = contract.keywords,
                creator = contract.creator,
                producer = contract.producer,
                creationDate = contract.creationDate,
                modificationDate = contract.modificationDate,
                pageCount = contract.pageCount,
                hasXmpMetadata = contract.hasXmpMetadata,
                isEncrypted = contract.isEncrypted
            )
            Result.success(result)
        } catch(e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e("PdfMetadataEngine: Error reading metadata", e)
            Result.failure(e)
        }
    }

    private fun isInternalCacheUri(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        val cacheRoot = runCatching { context.cacheDir.canonicalPath }.getOrNull() ?: return false
        val targetPath = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
        return targetPath == cacheRoot || targetPath.startsWith(cacheRoot + File.separator)
    }

    suspend fun writeOrSanitizeMetadata(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        newMetadata: DocumentMetadataInfo?,
        wipeAllMetadata: Boolean = false,
        recordHistory: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            SourcePreservationPolicy.requireSeparateDestination(context, sourcePdfUri, destPdfUri)
            val params = JSONObject()
            params.put("wipeAllMetadata", wipeAllMetadata)
            if (newMetadata != null) {
                val md = JSONObject()
                md.put("title", newMetadata.title)
                md.put("author", newMetadata.author)
                md.put("subject", newMetadata.subject)
                md.put("keywords", newMetadata.keywords)
                md.put("creator", newMetadata.creator)
                md.put("producer", newMetadata.producer)
                params.put("newMetadata", md)
            }
            PdfGateway.executeEngine(context, "METADATA_WRITE", sourcePdfUri, destPdfUri, params.toString())

            if (recordHistory && !isInternalCacheUri(context, destPdfUri)) {
                val actionName = if (wipeAllMetadata) "Sanitized PDF (No Metadata)" else "Updated Metadata PDF"
                val historyRepo = HistoryRepository(context)
                historyRepo.addHistoryItem(destPdfUri, FileUtils.getFileName(context, destPdfUri) ?: "document.pdf", actionName)
            }

            Result.success(true)
        } catch(e: Exception) {
            if (e is CancellationException) throw e
            AppLogger.e("PdfMetadataEngine: Error writing metadata", e)
            Result.failure(e)
        }
    }
}
