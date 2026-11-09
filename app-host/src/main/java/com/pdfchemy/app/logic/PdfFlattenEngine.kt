package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FlattenDiagnostic(
    val fieldCount: Int = 0,
    val annotationCount: Int = 0,
    val hasSignatures: Boolean = false
)

object PdfFlattenEngine {
    private val gson = Gson()

    /**
     * Inspects a PDF to count fillable form fields, signatures, and annotations.
     */
    suspend fun inspectFlattenElements(
        context: Context,
        pdfUri: Uri
    ): Result<FlattenDiagnostic> = withContext(Dispatchers.IO) {
        try {
            val contract = PdfGateway.executeEngineTyped<FlattenInspectContract>(context, "FLATTEN_INSPECT", pdfUri, null, "{}")
            val diag = FlattenDiagnostic(
                fieldCount = contract.fieldCount,
                annotationCount = contract.annotationCount,
                hasSignatures = contract.hasSignatures
            )
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
            val params = gson.toJson(mapOf(
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
