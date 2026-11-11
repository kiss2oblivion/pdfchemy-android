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
    val textRemovedLines: List<String>,
    val doc1PageIndex: Int? = pageIndex,
    val doc2PageIndex: Int? = pageIndex,
    val alignmentLabel: String = ""
)

data class DocumentDiffSummary(
    val totalPagesDoc1: Int,
    val totalPagesDoc2: Int,
    val identicalPages: Int,
    val modifiedPages: Int,
    val pageDiffs: List<PageDiffResult>
)

object PdfDiffEngine {

    data class PageAlignment(
        val doc1PageIndex: Int?,
        val doc2PageIndex: Int?,
        val similarity: Float
    )

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

            val alignments = alignPages(texts1, texts2)
            for ((idx, pair) in alignments.withIndex()) {
                val p1 = pair.doc1PageIndex
                val p2 = pair.doc2PageIndex
                var bmp1: Bitmap? = null
                var bmp2: Bitmap? = null
                try {
                    bmp1 = if (p1 != null) PdfEditor.renderPageBitmap(context, first.uri, p1, 720) else null
                    bmp2 = if (p2 != null) PdfEditor.renderPageBitmap(context, second.uri, p2, 720) else null

                    val lines1 = if (p1 != null) texts1.getOrNull(p1)?.lines()?.map(String::trim)?.filter(String::isNotBlank).orEmpty() else emptyList()
                    val lines2 = if (p2 != null) texts2.getOrNull(p2)?.lines()?.map(String::trim)?.filter(String::isNotBlank).orEmpty() else emptyList()

                    val (added, removed) = computeLineDiff(lines1, lines2)
                    val visual = if (bmp1 != null && bmp2 != null) {
                        generateVisualDiff(bmp1, bmp2)
                    } else if (bmp1 == null && bmp2 != null) {
                        100f to generateSinglePageTinted(bmp2, Color.argb(160, 46, 125, 50))
                    } else if (bmp1 != null && bmp2 == null) {
                        100f to generateSinglePageTinted(bmp1, Color.argb(160, 198, 40, 40))
                    } else {
                        100f to null
                    }

                    val label = when {
                        p1 != null && p2 != null -> "Page ${p1 + 1} vs ${p2 + 1}"
                        p1 != null -> "Page ${p1 + 1} (Removed in Doc 2)"
                        p2 != null -> "Page ${p2 + 1} (Added in Doc 2)"
                        else -> "Page ${idx + 1}"
                    }

                    diffs.add(
                        PageDiffResult(
                            pageIndex = idx,
                            diffPercent = visual.first,
                            diffBitmap = visual.second,
                            textAddedLines = added,
                            textRemovedLines = removed,
                            doc1PageIndex = p1,
                            doc2PageIndex = p2,
                            alignmentLabel = label
                        )
                    )
                } finally {
                    bmp1?.recycle()
                    bmp2?.recycle()
                }
            }

            val identical = diffs.count {
                it.diffPercent < 0.05f &&
                    it.textAddedLines.isEmpty() &&
                    it.textRemovedLines.isEmpty() &&
                    it.doc1PageIndex != null &&
                    it.doc2PageIndex != null
            }
            completed = true
            return DocumentDiffSummary(texts1.size, texts2.size, identical, diffs.size - identical, diffs)
        } finally {
            if (!completed) diffs.forEach { it.diffBitmap?.recycle() }
            snapshots.zip(listOf(uri1, uri2)).filter { (snapshot, original) -> snapshot.uri != original }.forEach { stager.release(it.first) }
            leases.forEach { it.close() }
        }
    }

    fun alignPages(texts1: List<String>, texts2: List<String>): List<PageAlignment> {
        val n = texts1.size
        val m = texts2.size

        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return (0 until m).map { PageAlignment(null, it, 0f) }
        if (m == 0) return (0 until n).map { PageAlignment(it, null, 0f) }

        val tokens1 = texts1.map { extractPageTokens(it) }
        val tokens2 = texts2.map { extractPageTokens(it) }

        val simMatrix = Array(n) { i ->
            FloatArray(m) { j ->
                computePageSimilarity(tokens1[i], tokens2[j], texts1[i], texts2[j], i == j)
            }
        }

        val dp = Array(n + 1) { FloatArray(m + 1) }

        for (i in 1..n) {
            for (j in 1..m) {
                val s = simMatrix[i - 1][j - 1]
                val matchScore = if (s >= 0.20f) {
                    (s * 10f) + (if (i == j) 0.5f else 0f)
                } else {
                    -1000f
                }
                val diag = dp[i - 1][j - 1] + matchScore
                val up = dp[i - 1][j]
                val left = dp[i][j - 1]
                dp[i][j] = maxOf(up, left, diag)
            }
        }

        val alignment = mutableListOf<PageAlignment>()
        var i = n
        var j = m

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                val s = simMatrix[i - 1][j - 1]
                val matchScore = if (s >= 0.20f) (s * 10f) + (if (i == j) 0.5f else 0f) else -1000f
                if (s >= 0.20f && kotlin.math.abs(dp[i][j] - (dp[i - 1][j - 1] + matchScore)) < 0.001f) {
                    alignment.add(PageAlignment(i - 1, j - 1, s))
                    i--
                    j--
                    continue
                }
            }
            if (j > 0 && (i == 0 || kotlin.math.abs(dp[i][j] - dp[i][j - 1]) < 0.001f)) {
                alignment.add(PageAlignment(null, j - 1, 0f))
                j--
            } else if (i > 0) {
                alignment.add(PageAlignment(i - 1, null, 0f))
                i--
            } else {
                break
            }
        }

        alignment.reverse()
        return alignment
    }

    private fun extractPageTokens(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        return text.lowercase()
            .split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun computePageSimilarity(
        tokens1: Set<String>,
        tokens2: Set<String>,
        raw1: String,
        raw2: String,
        isSameIndex: Boolean
    ): Float {
        if (tokens1.isEmpty() && tokens2.isEmpty()) {
            return if (raw1.trim() == raw2.trim() && raw1.isNotBlank()) 1.0f
            else if (isSameIndex) 0.8f
            else 0.0f
        }
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0f
        val intersectionSize = tokens1.intersect(tokens2).size
        val unionSize = tokens1.union(tokens2).size
        if (unionSize == 0) return 1.0f
        return intersectionSize.toFloat() / unionSize.toFloat()
    }

    private fun generateSinglePageTinted(source: Bitmap, tintColor: Int): Bitmap? {
        return try {
            val bmp = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawBitmap(source, 0f, 0f, null)
            canvas.drawColor(tintColor)
            bmp
        } catch (_: Exception) {
            null
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

    private fun computeLineDiff(lines1: List<String>, lines2: List<String>): Pair<List<String>, List<String>> {
        if (lines1.isEmpty()) return lines2 to emptyList<String>()
        if (lines2.isEmpty()) return emptyList<String>() to lines1
        if (lines1 == lines2) return emptyList<String>() to emptyList<String>()

        val maxLines = 600
        val l1 = if (lines1.size > maxLines) lines1.take(maxLines) else lines1
        val l2 = if (lines2.size > maxLines) lines2.take(maxLines) else lines2

        val n = l1.size
        val m = l2.size
        val dp = Array(n + 1) { IntArray(m + 1) }

        for (i in 1..n) {
            val item1 = l1[i - 1]
            for (j in 1..m) {
                dp[i][j] = if (item1 == l2[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var i = n
        var j = m

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && l1[i - 1] == l2[j - 1]) {
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                added.add(l2[j - 1])
                j--
            } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                removed.add(l1[i - 1])
                i--
            }
        }

        added.reverse()
        removed.reverse()
        return added to removed
    }
}
