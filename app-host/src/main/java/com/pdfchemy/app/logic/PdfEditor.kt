// =================================================================================================
// [FEATURE: Visual PDF Editor & Freehand Annotation Engine] (FEATURES_REGISTRY Android  4)
// Highlighting, freehand pen/pencil drawing, shapes, watermarking, and text annotations.
// =================================================================================================

package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Canvas
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class DualPageBitmaps(val leftPage: Bitmap?, val rightPage: Bitmap?)

object PdfEditor {
    suspend fun getPageCount(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        PdfGateway.executeEngineTyped<PageCountContract>(context, "GET_PAGE_COUNT", uri, null, "{}").pageCount
    }
    suspend fun renderPageBitmap(context: Context, uri: Uri, pageIndex: Int, targetWidth: Int = 1080): Bitmap? =
        com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderUriToBitmap(context, uri, pageIndex, targetWidth)

    suspend fun renderDualPageBitmaps(
        context: Context,
        uri: Uri,
        leftIndex: Int,
        rightIndex: Int,
        targetWidth: Int = 720
    ): DualPageBitmaps = withContext(Dispatchers.IO) {
        val leftBmp = renderPageBitmap(context, uri, leftIndex, targetWidth)
        val rightBmp = if (rightIndex >= 0) renderPageBitmap(context, uri, rightIndex, targetWidth) else null
        DualPageBitmaps(leftPage = leftBmp, rightPage = rightBmp)
    }

    suspend fun exportModifiedPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        modifications: Map<Int, PageModification>
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val json = com.google.gson.Gson().toJson(modifications)
            PdfGateway.executeEngine(context, "EDITOR_EXPORT", sourceUri, destUri, json)
            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfEditor: failed to export modified PDF", e)
            Result.failure(e)
        }
    }

    fun renderAnnotationOverlayBitmap(mod: PageModification, targetWidth: Int, targetHeight: Int): Bitmap? =
        AnnotationRenderer.render(mod, targetWidth, targetHeight)
}
