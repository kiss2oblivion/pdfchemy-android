package com.pdfchemy.app.logic

import kotlinx.coroutines.ensureActive
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.pdfchemy.app.security.SecurityLimits

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
            val first = stager.stageDocumentCancellableWithLease(context, uri1).also { snapshots.add(it.document); leases.add(it.lease) }.document
            val second = stager.stageDocumentCancellableWithLease(context, uri2).also { snapshots.add(it.document); leases.add(it.lease) }.document
            val texts1 = JailEngineBridge.callTyped<TextPagesContract>(context, "TEXT_PAGES", first.uri, null).pages
            val texts2 = JailEngineBridge.callTyped<TextPagesContract>(context, "TEXT_PAGES", second.uri, null).pages

            val comparisonContext = kotlinx.coroutines.currentCoroutineContext()
            val alignments = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                alignPagesChecked(texts1, texts2) { comparisonContext.ensureActive() }
            }
            var retainedBitmapBytes = 0L
            for ((idx, pair) in alignments.withIndex()) {
                comparisonContext.ensureActive()
                val p1 = pair.doc1PageIndex
                val p2 = pair.doc2PageIndex
                var bmp1: Bitmap? = null
                var bmp2: Bitmap? = null
                try {
                    // Null is legitimate only for a page absent from the alignment.
                    // A missing render of an existing page invalidates the comparison.
                    bmp1 = p1?.let { requireRenderedPage(it, PdfEditor.renderPageBitmap(context, first.uri, it, 720)) }
                    bmp2 = p2?.let { requireRenderedPage(it, PdfEditor.renderPageBitmap(context, second.uri, it, 720)) }

                    val canvasWidth = maxOf(bmp1?.width ?: 0, bmp2?.width ?: 0)
                    val canvasHeight = maxOf(bmp1?.height ?: 0, bmp2?.height ?: 0)
                    SecurityLimits.requirePixels(canvasWidth, canvasHeight)
                    val nextBytes = canvasWidth.toLong() * canvasHeight * 4L
                    require(nextBytes <= MAX_RETAINED_DIFF_BYTES - retainedBitmapBytes) {
                        "Comparison exceeds its preview memory budget; compare smaller documents"
                    }

                    val lines1 = if (p1 != null) texts1.getOrNull(p1)?.lines()?.map(String::trim)?.filter(String::isNotBlank).orEmpty() else emptyList()
                    val lines2 = if (p2 != null) texts2.getOrNull(p2)?.lines()?.map(String::trim)?.filter(String::isNotBlank).orEmpty() else emptyList()

                    val (added, removed) = computeLineDiff(lines1, lines2)
                    val visual = if (bmp1 != null && bmp2 != null) {
                        generateVisualDiff(bmp1, bmp2) { comparisonContext.ensureActive() }
                    } else if (bmp1 == null && bmp2 != null) {
                        100f to generateSinglePageTinted(bmp2, Color.argb(160, 46, 125, 50))
                    } else if (bmp1 != null && bmp2 == null) {
                        100f to generateSinglePageTinted(bmp1, Color.argb(160, 198, 40, 40))
                    } else {
                        error("Alignment contains no page")
                    }
                    retainedBitmapBytes += nextBytes

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
                it.diffPercent == 0f &&
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

    fun alignPages(texts1: List<String>, texts2: List<String>): List<PageAlignment> =
        alignPagesChecked(texts1, texts2) {}

    private fun alignPagesChecked(texts1: List<String>, texts2: List<String>, checkCancellation: () -> Unit): List<PageAlignment> {
        checkCancellation()
        val n = texts1.size
        val m = texts2.size
        require(n <= com.pdfchemy.app.security.SecurityLimits.MAX_PAGE_COUNT &&
            m <= com.pdfchemy.app.security.SecurityLimits.MAX_PAGE_COUNT) { "Too many pages to compare" }
        require((n.toLong() + 1) * (m.toLong() + 1) <= 1_000_000L) {
            "Page alignment exceeds its memory budget; compare smaller page ranges"
        }

        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return (0 until m).map { PageAlignment(null, it, 0f) }
        if (m == 0) return (0 until n).map { PageAlignment(it, null, 0f) }

        val tokens1 = texts1.map { extractPageTokens(it) }
        val tokens2 = texts2.map { extractPageTokens(it) }

        val simMatrix = Array(n) { i ->
            checkCancellation()
            FloatArray(m) { j ->
                computePageSimilarity(tokens1[i], tokens2[j], texts1[i], texts2[j], i == j)
            }
        }

        val dp = Array(n + 1) { FloatArray(m + 1) }

        for (i in 1..n) {
            checkCancellation()
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

    // A conservative cap on previews retained in the summary, not a total heap guarantee.
    private const val MAX_RETAINED_DIFF_BYTES = 32L * 1024 * 1024

    internal fun <T : Any> requireRenderedPage(index: Int, rendered: T?): T =
        checkNotNull(rendered) { "Unable to render page ${index + 1}" }

    private fun generateSinglePageTinted(source: Bitmap, tintColor: Int): Bitmap {
        val bmp = createBitmap(source.width, source.height)
        try {
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawBitmap(source, 0f, 0f, null)
            canvas.drawColor(tintColor)
            return bmp
        } catch (failure: Throwable) {
            bmp.recycle()
            throw failure
        }
    }

    private fun generateVisualDiff(bmp1: Bitmap, bmp2: Bitmap, checkCancellation: () -> Unit): Pair<Float, Bitmap> {
        val width = maxOf(bmp1.width, bmp2.width)
        val height = maxOf(bmp1.height, bmp2.height)
        SecurityLimits.requirePixels(width, height)
        val diff = createBitmap(width, height)
        try {
            val percent = compareRasterRows(
                bmp1.width, bmp1.height, bmp2.width, bmp2.height,
                read1 = { y, row -> bmp1.getPixels(row, 0, bmp1.width, 0, y, bmp1.width, 1) },
                read2 = { y, row -> bmp2.getPixels(row, 0, bmp2.width, 0, y, bmp2.width, 1) },
                write = { y, row -> diff.setPixels(row, 0, width, 0, y, width, 1) },
                checkCancellation = checkCancellation
            )
            return percent to diff
        } catch (failure: Throwable) {
            diff.recycle()
            throw failure
        }
    }

    /**
     * Compare the union of both rendered page areas. Pixels outside both pages
     * are not counted. Non-overlapping page regions are always differences.
     * Row buffers keep working memory proportional to width rather than area.
     */
    internal fun compareRasterRows(
        width1: Int, height1: Int, width2: Int, height2: Int,
        read1: (Int, IntArray) -> Unit,
        read2: (Int, IntArray) -> Unit,
        write: (Int, IntArray) -> Unit,
        checkCancellation: () -> Unit = {}
    ): Float {
        SecurityLimits.requirePixels(width1, height1)
        SecurityLimits.requirePixels(width2, height2)
        val width = maxOf(width1, width2)
        val height = maxOf(height1, height2)
        SecurityLimits.requirePixels(width, height)
        val row1 = IntArray(width1)
        val row2 = IntArray(width2)
        val out = IntArray(width)
        var compared = 0L
        var changed = 0L
        for (y in 0 until height) {
            checkCancellation()
            if (y < height1) read1(y, row1)
            if (y < height2) read2(y, row2)
            for (x in 0 until width) {
                val in1 = x < width1 && y < height1
                val in2 = x < width2 && y < height2
                if (!in1 && !in2) {
                    out[x] = 0 // transparent area outside both pages
                    continue
                }
                compared++
                if (!in1 || !in2) {
                    changed++
                    out[x] = if (in2) 0xA02E7D32.toInt() else 0xA0C62828.toInt()
                    continue
                }
                val c1 = row1[x]
                val c2 = row2[x]
                val r = (c2 ushr 16) and 255
                val g = (c2 ushr 8) and 255
                val b = c2 and 255
                val different = kotlin.math.abs(((c1 ushr 16) and 255) - r) > 30 ||
                    kotlin.math.abs(((c1 ushr 8) and 255) - g) > 30 ||
                    kotlin.math.abs((c1 and 255) - b) > 30
                if (different) {
                    changed++
                    out[x] = 0xE6EB1E64.toInt()
                } else {
                    val gray = (r + g + b) / 3
                    out[x] = (120 shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
            write(y, out)
        }
        return (changed.toDouble() * 100.0 / compared).toFloat()
    }

    internal fun computeLineDiff(lines1: List<String>, lines2: List<String>): Pair<List<String>, List<String>> {
        if (lines1.isEmpty()) return lines2 to emptyList<String>()
        if (lines2.isEmpty()) return emptyList<String>() to lines1
        if (lines1 == lines2) return emptyList<String>() to emptyList<String>()

        // Strip the shared prefix/suffix before allocating the bounded LCS table.
        // Never silently discard differences beyond the previous 600-line cutoff.
        var prefix = 0
        while (prefix < minOf(lines1.size, lines2.size) && lines1[prefix] == lines2[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(lines1.size, lines2.size) - prefix &&
            lines1[lines1.lastIndex - suffix] == lines2[lines2.lastIndex - suffix]) suffix++
        val l1 = lines1.subList(prefix, lines1.size - suffix)
        val l2 = lines2.subList(prefix, lines2.size - suffix)
        if (l1.isEmpty()) return l2.toList() to emptyList()
        if (l2.isEmpty()) return emptyList<String>() to l1.toList()
        require((l1.size.toLong() + 1) * (l2.size.toLong() + 1) <= 601L * 601L) {
            "Text comparison exceeds its memory budget; compare smaller page ranges"
        }

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
