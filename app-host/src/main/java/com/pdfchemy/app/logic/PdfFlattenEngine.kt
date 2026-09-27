package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

data class FlattenDiagnostic(
    val fieldCount: Int = 0,
    val annotationCount: Int = 0,
    val hasSignatures: Boolean = false
)

object PdfFlattenEngine {

    /**
     * Inspects a PDF to count fillable form fields, signatures, and annotations.
     */
    suspend fun inspectFlattenElements(
        context: Context,
        pdfUri: Uri
    ): Result<FlattenDiagnostic> = withContext(Dispatchers.IO) {
        try {
            val json = PdfGateway.executeEngine(context, "FLATTEN_INSPECT", pdfUri, null, "{}")
            val diag = com.google.gson.Gson().fromJson(json, FlattenDiagnostic::class.java)
            Result.success(diag)
        } catch (e: Exception) {
            AppLogger.e("PdfFlattenEngine: Error inspecting elements", e)
            Result.failure(e)
        }
    }

    /**
     * Flattens AcroForm fields, signatures, and/or annotations into immutable vector and high-resolution content streams.
     */
    suspend fun flattenPdf(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        flattenForms: Boolean = true,
        flattenAnnotations: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val params = com.google.gson.Gson().toJson(mapOf(
                "flattenForms" to flattenForms,
                "flattenAnnotations" to flattenAnnotations
            ))
            PdfGateway.executeEngine(context, "FLATTEN_APPLY", sourcePdfUri, destPdfUri, params)

            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(
                destPdfUri,
                FileUtils.getFileName(context, destPdfUri) ?: "flattened.pdf",
                "Flatten & Lock PDF"
            )

            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfFlattenEngine: Error flattening PDF", e)
            Result.failure(e)
        }
    }
}
