package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color

data class PageDiffResult(
    val pageIndex: Int,
    val diffPercent: Float,
    val diffBitmap: Bitmap?,
    val textAddedLines: List<String>,
    val textRemovedLines: List<String>
)

data class DocumentDiffSummary(
    val totalPagesDoc1: Int,
    val totalPagesDoc2: Int,
    val identicalPages: Int,
    val modifiedPages: Int,
    val pageDiffs: List<PageDiffResult>
)

object PdfDiffEngine {
    suspend fun compareDocuments(context: Context, uri1: Uri, uri2: Uri): DocumentDiffSummary {
        val stager = com.pdfchemy.app.utils.DocumentStager
        val snapshots = mutableListOf<com.pdfchemy.app.jail.StagedPdf>()
        val leases = mutableListOf<java.io.Closeable>()
        val diffs = mutableListOf<PageDiffResult>()
        var completed = false
        try {
            val first = stager.stageDocumentCancellable(context, uri1).also { snapshots.add(it); leases.add(stager.retain(it)) }
            val second = stager.stageDocumentCancellable(context, uri2).also { snapshots.add(it); leases.add(stager.retain(it)) }
            val texts1 = JailEngineBridge.callTyped<TextPagesContract>(context, "TEXT_PAGES", first.uri, null).pages
            val texts2 = JailEngineBridge.callTyped<TextPagesContract>(context, "TEXT_PAGES", second.uri, null).pages
            for (page in 0 until maxOf(texts1.size, texts2.size)) {
                var bmp1: Bitmap? = null
                var bmp2: Bitmap? = null
                try {
                    bmp1 = if (page < texts1.size) PdfEditor.renderPageBitmap(context, first.uri, page, 720) else null
                    bmp2 = if (page < texts2.size) PdfEditor.renderPageBitmap(context, second.uri, page, 720) else null
                    val lines1 = texts1.getOrNull(page)?.lines()?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
                    val lines2 = texts2.getOrNull(page)?.lines()?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
                    val visual = if (bmp1 != null && bmp2 != null) generateVisualDiff(bmp1, bmp2) else 100f to null
                    diffs.add(PageDiffResult(page, visual.first, visual.second, lines2.filter { it !in lines1 }, lines1.filter { it !in lines2 }))
                } finally { bmp1?.recycle(); bmp2?.recycle() }
            }
            val identical = diffs.count { it.diffPercent < 0.05f && it.textAddedLines.isEmpty() && it.textRemovedLines.isEmpty() }
            completed = true
            return DocumentDiffSummary(texts1.size, texts2.size, identical, diffs.size - identical, diffs)
        } finally {
            if (!completed) diffs.forEach { it.diffBitmap?.recycle() }
            snapshots.zip(listOf(uri1, uri2)).filter { (snapshot, original) -> snapshot.uri != original }.forEach { stager.release(it.first) }
            leases.forEach { it.close() }
        }
    }
    private fun generateVisualDiff(bmp1: Bitmap, bmp2: Bitmap): Pair<Float, Bitmap?> {
        return try {
            val width = minOf(bmp1.width, bmp2.width)
            val height = minOf(bmp1.height, bmp2.height)
            val diffBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            var diffPixelCount = 0
            val totalPixels = width * height

            val pixels1 = IntArray(width * height)
            val pixels2 = IntArray(width * height)
            val diffPixels = IntArray(width * height)

            bmp1.getPixels(pixels1, 0, width, 0, 0, width, height)
            bmp2.getPixels(pixels2, 0, width, 0, 0, width, height)

            for (idx in 0 until totalPixels) {
                val c1 = pixels1[idx]
                val c2 = pixels2[idx]

                val rDiff = kotlin.math.abs(Color.red(c1) - Color.red(c2))
                val gDiff = kotlin.math.abs(Color.green(c1) - Color.green(c2))
                val bDiff = kotlin.math.abs(Color.blue(c1) - Color.blue(c2))

                if (rDiff > 30 || gDiff > 30 || bDiff > 30) {
                    diffPixelCount++
                    // Highlight modified pixel with vibrant magenta / red tint
                    diffPixels[idx] = Color.argb(230, 235, 30, 100)
                } else {
                    // Muted grayscale background
                    val gray = (Color.red(c2) + Color.green(c2) + Color.blue(c2)) / 3
                    diffPixels[idx] = Color.argb(120, gray, gray, gray)
                }
            }

            diffBmp.setPixels(diffPixels, 0, width, 0, 0, width, height)
            val percent = if (totalPixels > 0) (diffPixelCount.toFloat() / totalPixels.toFloat()) * 100f else 0f
            Pair(percent, diffBmp)
        } catch (e: Exception) {
            Pair(0f, null)
        }
    }
}
