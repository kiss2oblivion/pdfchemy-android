package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Result representing the outcome of a table extraction operation.
 */
sealed class TableExtractionResult {
    data class Success(val csv: String, val rowCount: Int) : TableExtractionResult()
    data class Empty(val pageIndex: Int? = null) : TableExtractionResult()
    data class Failure(val cause: Throwable) : TableExtractionResult()
    object Cancelled : TableExtractionResult()
}

/**
 * 100% Offline & Local-First PDF Table & Data Extractor.
 * Extracts tabular data, forms, and spreadsheets from PDF documents directly to RFC 4180 CSV
 * using spatial 2D text clustering without lossy cloud dependencies.
 */
object PdfTableExtractorEngine {

    /**
     * Extracts tabular data from a PDF document into an RFC 4180 CSV string.
     * Returns a strongly-typed [TableExtractionResult].
     */
    suspend fun extractTables(
        context: Context,
        sourceUri: Uri,
        pageIndex: Int? = null,
        safeMode: Boolean = true
    ): TableExtractionResult = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_table_extract_${UUID.randomUUID()}.csv")
        try {
            val destUri = Uri.fromFile(tempFile)
            val params = JSONObject().apply {
                put("safeMode", safeMode)
                if (pageIndex != null) {
                    put("pageIndex", pageIndex)
                }
            }

            val contract = PdfGateway.executeEngineTyped<StandardOutputContract>(
                context,
                "TABLE_EXTRACT",
                sourceUri,
                destUri,
                params.toString()
            )

            if (!contract.success) {
                return@withContext TableExtractionResult.Failure(
                    IllegalStateException("Worker reported table extraction failure")
                )
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                return@withContext TableExtractionResult.Empty(pageIndex)
            }

            val csv = com.pdfchemy.app.jail.WorkerResponseValidator.parseCsvTableFile(tempFile)
            if (csv.isBlank()) {
                return@withContext TableExtractionResult.Empty(pageIndex)
            }

            val rows = CsvParser.parse(csv)
            if (rows.isEmpty()) {
                return@withContext TableExtractionResult.Empty(pageIndex)
            }

            TableExtractionResult.Success(csv, rows.size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            TableExtractionResult.Failure(t)
        } finally {
            if (tempFile.exists()) {
                runCatching { tempFile.delete() }
            }
        }
    }

    /**
     * Extracts tabular data directly to a destination URI (e.g., user-chosen SAF document).
     */
    suspend fun extractTablesToFile(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        pageIndex: Int? = null,
        safeMode: Boolean = true
    ): TableExtractionResult = withContext(Dispatchers.IO) {
        val params = JSONObject().apply {
            put("safeMode", safeMode)
            if (pageIndex != null) {
                put("pageIndex", pageIndex)
            }
        }

        try {
            val contract = PdfGateway.executeEngineTyped<StandardOutputContract>(
                context,
                "TABLE_EXTRACT",
                sourceUri,
                destUri,
                params.toString()
            )

            if (!contract.success) {
                return@withContext TableExtractionResult.Failure(
                    IllegalStateException("Worker reported table extraction failure")
                )
            }

            val length = runCatching {
                context.contentResolver.openFileDescriptor(destUri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)

            if (length == 0L || contract.count == 0) {
                TableExtractionResult.Empty(pageIndex)
            } else {
                TableExtractionResult.Success("", contract.count)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            TableExtractionResult.Failure(t)
        }
    }

    /**
     * Convenience method returning CSV string, preserving backward compatibility.
     * Throws any extraction failure or cancellation directly.
     */
    suspend fun extractTablesToCsv(
        context: Context,
        sourceUri: Uri,
        pageIndex: Int? = null,
        safeMode: Boolean = true
    ): String {
        return when (val result = extractTables(context, sourceUri, pageIndex, safeMode)) {
            is TableExtractionResult.Success -> result.csv
            is TableExtractionResult.Empty -> ""
            is TableExtractionResult.Failure -> throw result.cause
            is TableExtractionResult.Cancelled -> throw kotlinx.coroutines.CancellationException("Table extraction cancelled")
        }
    }

    /**
     * Convenience method returning boolean for file extraction, preserving backward compatibility.
     * Re-throws CancellationException to maintain coroutine cancellation semantics.
     */
    suspend fun extractTablesToCsvFile(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        pageIndex: Int? = null,
        safeMode: Boolean = true
    ): Boolean {
        return when (extractTablesToFile(context, sourceUri, destUri, pageIndex, safeMode)) {
            is TableExtractionResult.Success, is TableExtractionResult.Empty -> true
            is TableExtractionResult.Failure -> false
            is TableExtractionResult.Cancelled -> false
        }
    }
}
