package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.utils.AppLogger
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun compareDocuments(
        context: Context,
        uri1: Uri,
        uri2: Uri
    ): DocumentDiffSummary = withContext(Dispatchers.IO) {
        var pfd1: ParcelFileDescriptor? = null
        var pfd2: ParcelFileDescriptor? = null
        var renderer1: PdfRenderer? = null
        var renderer2: PdfRenderer? = null
        var doc1: PDDocument? = null
        var doc2: PDDocument? = null

        val pageDiffs = mutableListOf<PageDiffResult>()
        var identicalCount = 0
        var modifiedCount = 0

        try {
            pfd1 = context.contentResolver.openFileDescriptor(uri1, "r")
            pfd2 = context.contentResolver.openFileDescriptor(uri2, "r")

            try {
                if (pfd1 != null) renderer1 = PdfRenderer(pfd1)
            } catch (e: Exception) {
                // Ignore in headless/Robolectric test environments
            }

            try {
                if (pfd2 != null) renderer2 = PdfRenderer(pfd2)
            } catch (e: Exception) {
                // Ignore in headless/Robolectric test environments
            }

            context.contentResolver.openInputStream(uri1)?.use { s1 -> doc1 = PDDocument.load(s1, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()) }
            context.contentResolver.openInputStream(uri2)?.use { s2 -> doc2 = PDDocument.load(s2, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()) }

            val total1 = (renderer1?.pageCount ?: 0).takeIf { it > 0 } ?: (doc1?.numberOfPages ?: 0)
            val total2 = (renderer2?.pageCount ?: 0).takeIf { it > 0 } ?: (doc2?.numberOfPages ?: 0)
            val maxPages = maxOf(total1, total2)

            val allPagesText1 = doc1?.let { PdfTextExtractor.extractAllPagesText(it) } ?: emptyList()
            val allPagesText2 = doc2?.let { PdfTextExtractor.extractAllPagesText(it) } ?: emptyList()

            for (i in 0 until maxPages) {
                val hasPage1 = i < total1
                val hasPage2 = i < total2

                var bmp1: Bitmap? = null
                var bmp2: Bitmap? = null

                if (hasPage1 && renderer1 != null && i < renderer1!!.pageCount) {
                    try {
                        val p1 = renderer1!!.openPage(i)
                        val scale = minOf(1.0f, 720f / maxOf(p1.width, p1.height))
                        val w = (p1.width * scale).toInt().coerceAtLeast(1)
                        val h = (p1.height * scale).toInt().coerceAtLeast(1)
                        bmp1 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        p1.render(bmp1, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        p1.close()
                    } catch (e: Exception) {
                        bmp1 = null
                    }
                }

                if (hasPage2 && renderer2 != null && i < renderer2!!.pageCount) {
                    try {
                        val p2 = renderer2!!.openPage(i)
                        val scale = minOf(1.0f, 720f / maxOf(p2.width, p2.height))
                        val w = (p2.width * scale).toInt().coerceAtLeast(1)
                        val h = (p2.height * scale).toInt().coerceAtLeast(1)
                        bmp2 = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        p2.render(bmp2, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        p2.close()
                    } catch (e: Exception) {
                        bmp2 = null
                    }
                }

                val text1 = allPagesText1.getOrNull(i)?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                val text2 = allPagesText2.getOrNull(i)?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

                val addedLines = text2.filter { it !in text1 }
                val removedLines = text1.filter { it !in text2 }

                var diffPercent = 0f
                var diffBmp: Bitmap? = null

                if (bmp1 != null && bmp2 != null) {
                    val diffData = generateVisualDiff(bmp1, bmp2)
                    diffPercent = diffData.first
                    diffBmp = diffData.second
                } else {
                    diffPercent = 100f
                }

                if (diffPercent < 0.05f && addedLines.isEmpty() && removedLines.isEmpty()) {
                    identicalCount++
                } else {
                    modifiedCount++
                }

                pageDiffs.add(
                    PageDiffResult(
                        pageIndex = i,
                        diffPercent = diffPercent,
                        diffBitmap = diffBmp,
                        textAddedLines = addedLines,
                        textRemovedLines = removedLines
                    )
                )

                bmp1?.recycle()
                bmp2?.recycle()
            }

            DocumentDiffSummary(
                totalPagesDoc1 = total1,
                totalPagesDoc2 = total2,
                identicalPages = identicalCount,
                modifiedPages = modifiedCount,
                pageDiffs = pageDiffs
            )
        } catch (e: Exception) {
            AppLogger.e("Failed to compare PDF documents: ${e.message}", e)
            DocumentDiffSummary(0, 0, 0, 0, emptyList())
        } finally {
            doc1?.close()
            doc2?.close()
            renderer1?.close()
            renderer2?.close()
            pfd1?.close()
            pfd2?.close()
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
