package com.pdfchemy.app.jail.engines

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.jail.CapabilityIo
import com.pdfchemy.app.jail.JailMemory
import com.pdfchemy.app.logic.FindReplaceSummary
import com.pdfchemy.app.security.BoundedOutputStream
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object PdfFindAndReplaceEngine {
    suspend fun findOccurrences(context: Context, pdfUri: Uri, query: String,
                                matchCase: Boolean = false): FindReplaceSummary = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext FindReplaceSummary(0, 0, emptyList())
        val operationContext = currentCoroutineContext()
        PDFBoxResourceLoader.init(context)
        requireNotNull(CapabilityIo.input(context, pdfUri)) { "Cannot open source PDF" }.use { input ->
            PDDocument.load(input, JailMemory.settings()).use { document ->
                PdfTextContentEditor(document, query, matchCase, checkpoint = { operationContext.ensureActive() }).execute()
            }
        }
    }

    suspend fun replaceAll(
        context: Context, sourcePdfUri: Uri, destPdfUri: Uri, findText: String, replaceText: String,
        matchCase: Boolean = false,
        maskColorRgb: Triple<Float, Float, Float> = Triple(1f, 1f, 1f),
        textColorRgb: Triple<Float, Float, Float> = Triple(0f, 0f, 0f)
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            require(findText.isNotBlank()) { "Search query cannot be blank" }
            val operationContext = currentCoroutineContext()
            PDFBoxResourceLoader.init(context)
            val count = requireNotNull(CapabilityIo.input(context, sourcePdfUri)) { "Cannot open source PDF" }.use { input ->
                PDDocument.load(input, JailMemory.settings()).use { document ->
                    val summary = PdfTextContentEditor(document, findText, matchCase, replaceText, maskColorRgb, textColorRgb) { operationContext.ensureActive() }.execute()
                    requireNotNull(CapabilityIo.output(context, destPdfUri)) { "Cannot open destination PDF" }.use { output ->
                        BoundedOutputStream(output).use(document::save)
                    }
                    summary.totalMatches
                }
            }
            Result.success(count)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
}
