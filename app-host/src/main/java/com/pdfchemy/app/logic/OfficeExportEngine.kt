package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject



object OfficeExportEngine {

    /**
     * Converts a PDF to a Microsoft Word (.docx) document via isolated process.
     */
    suspend fun exportToWord(
        context: Context,
        sourceUri: Uri,
        destUri: Uri
    ): Result<OfficeExportReport> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<OfficeExportContract>(context, "OFFICE_WORD", sourceUri, destUri, "{}")
            if (contract.success) {
                Result.success(
                    OfficeExportReport(
                        format = OfficeFormat.WORD,
                        pageCount = contract.pageCount,
                        outputSizeBytes = contract.outputSizeBytes,
                        itemsExtracted = contract.itemsExtracted
                    )
                )
            } else {
                Result.failure(Exception("Unknown Word export failure"))
            }
        } catch (e: Exception) {
            AppLogger.e("OfficeExportEngine: Error exporting to Word via gateway", e)
            Result.failure(e)
        }
    }

    /**
     * Converts a PDF to a Microsoft Excel (.xlsx) workbook via isolated process.
     */
    suspend fun exportToExcel(
        context: Context,
        sourceUri: Uri,
        destUri: Uri
    ): Result<OfficeExportReport> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<OfficeExportContract>(context, "OFFICE_EXCEL", sourceUri, destUri, "{}")
            if (contract.success) {
                Result.success(
                    OfficeExportReport(
                        format = OfficeFormat.EXCEL,
                        pageCount = contract.pageCount,
                        outputSizeBytes = contract.outputSizeBytes,
                        itemsExtracted = contract.itemsExtracted
                    )
                )
            } else {
                Result.failure(Exception("Unknown Excel export failure"))
            }
        } catch (e: Exception) {
            AppLogger.e("OfficeExportEngine: Error exporting to Excel via gateway", e)
            Result.failure(e)
        }
    }

    /**
     * Converts a PDF to a Microsoft PowerPoint (.pptx) presentation via isolated process.
     */
    suspend fun exportToPowerPoint(
        context: Context,
        sourceUri: Uri,
        destUri: Uri
    ): Result<OfficeExportReport> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<OfficeExportContract>(context, "OFFICE_PPT", sourceUri, destUri, "{}")
            if (contract.success) {
                Result.success(
                    OfficeExportReport(
                        format = OfficeFormat.POWERPOINT,
                        pageCount = contract.pageCount,
                        outputSizeBytes = contract.outputSizeBytes,
                        itemsExtracted = contract.itemsExtracted
                    )
                )
            } else {
                Result.failure(Exception("Unknown PowerPoint export failure"))
            }
        } catch (e: Exception) {
            AppLogger.e("OfficeExportEngine: Error exporting to PowerPoint via gateway", e)
            Result.failure(e)
        }
    }
}
