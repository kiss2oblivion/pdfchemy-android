package com.pdfchemy.app.logic

import kotlinx.coroutines.ensureActive
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.pdfchemy.app.security.SecurityLimits
import com.pdfchemy.app.utils.DocumentStager
import com.pdfchemy.app.jail.StagedPdf
import java.io.Closeable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PageDiffResult(
    val pageIndex: Int,
    val diffPercent: Float,
    val textAddedLines: List<String>,
    val textRemovedLines: List<String>,
    val doc1PageIndex: Int? = pageIndex,
    val doc2PageIndex: Int? = pageIndex,
    val alignmentLabel: String = "",
    val geometryChanged: Boolean = false,
    val geometry1: ComparePageGeometry? = null,
    val geometry2: ComparePageGeometry? = null,
    val rasterWidth: Int = 0,
    val rasterHeight: Int = 0,
    val regions: List<CompareRegion> = emptyList()
) {
    val hasDetectedChanges: Boolean get() = diffPercent != 0f || geometryChanged ||
        textAddedLines.isNotEmpty() || textRemovedLines.isNotEmpty() ||
        doc1PageIndex == null || doc2PageIndex == null
}

/** Owns immutable staged documents until the result is dismissed. No page bitmaps are retained. */
class DocumentDiffSummary internal constructor(
    val totalPagesDoc1: Int,
    val totalPagesDoc2: Int,
    val identicalPages: Int,
    val modifiedPages: Int,
    val pageDiffs: List<PageDiffResult>,
    val sensitivity: CompareSensitivity,
    private val sources: ComparisonPreviewSources
) : Closeable {
    private val previewMutex = Mutex()

    suspend fun renderPreview(context: Context, index: Int): Bitmap =
        previewMutex.withLock {
            val page = pageDiffs[index]
            sources.acquire().use {
                PdfDiffEngine.renderPreview(context, sources.first.uri, sources.second.uri, page, sensitivity)
            }
        }

    override fun close() = sources.close()
}

/** The session holds one lease per input. An in-flight preview takes its own leases
 * before the session can be closed, so closing never invalidates a running read. */
internal class ComparisonPreviewSources(
    val first: StagedPdf,
    val second: StagedPdf,
    private val releaseSession: () -> Unit
) : Closeable {
    private var closed = false

    @Synchronized fun acquire(): Closeable {
        check(!closed) { "Comparison session is closed" }
        val a = DocumentStager.retain(first)
        try {
            val b = DocumentStager.retain(second)
            return Closeable { try { b.close() } finally { a.close() } }
        } catch (failure: Throwable) {
            a.close()
            throw failure
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        releaseSession()
    }
}

object PdfDiffEngine {

    data class PageAlignment(
        val doc1PageIndex: Int?,
        val doc2PageIndex: Int?,
        val similarity: Float
    )

    suspend fun compareDocuments(
        context: Context, uri1: Uri, uri2: Uri,
        sensitivity: CompareSensitivity = CompareSensitivity.BALANCED
    ): DocumentDiffSummary {
        val stager = com.pdfchemy.app.utils.DocumentStager
        val snapshots = mutableListOf<com.pdfchemy.app.jail.StagedPdf>()
        val leases = mutableListOf<java.io.Closeable>()
        val diffs = mutableListOf<PageDiffResult>()
        var completed = false
        fun releaseInputs() {
            try {
                snapshots.zip(listOf(uri1, uri2)).filter { (snapshot, original) -> snapshot.uri != original }
                    .forEach { stager.release(it.first) }
            } finally {
                leases.forEach { it.close() }
            }
        }
        try {
            val first = stager.stageDocumentCancellableWithLease(context, uri1).also { snapshots.add(it.document); leases.add(it.lease) }.document
            val second = stager.stageDocumentCancellableWithLease(context, uri2).also { snapshots.add(it.document); leases.add(it.lease) }.document
            val profile1 = JailEngineBridge.callTyped<TextPagesContract>(context, "TEXT_PAGES", first.uri, null, mapOf("includeGeometry" to true))
            val profile2 = JailEngineBridge.callTyped<TextPagesContract>(context, "TEXT_PAGES", second.uri, null, mapOf("includeGeometry" to true))
            val texts1 = profile1.pages
            val texts2 = profile2.pages
            val geometry1 = requireNotNull(profile1.geometry) { "Missing source page geometry" }
            val geometry2 = requireNotNull(profile2.geometry) { "Missing modified page geometry" }
            require(geometry1.size == texts1.size && geometry2.size == texts2.size)
            requireAlignmentBudget(texts1.size, texts2.size)

            val comparisonContext = kotlinx.coroutines.currentCoroutineContext()
            val fingerprints1 = scannedFingerprints(context, first.uri, texts1)
            val fingerprints2 = scannedFingerprints(context, second.uri, texts2)
            val alignments = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                alignPagesChecked(texts1, texts2, fingerprints1, fingerprints2) { comparisonContext.ensureActive() }
            }
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

                    val lines1 = if (p1 != null) texts1.getOrNull(p1)?.lines()?.map(String::trim)?.filter(String::isNotBlank).orEmpty() else emptyList()
                    val lines2 = if (p2 != null) texts2.getOrNull(p2)?.lines()?.map(String::trim)?.filter(String::isNotBlank).orEmpty() else emptyList()

                    val (added, removed) = computeLineDiff(lines1, lines2)
                    // Compute the score without allocating an overlay bitmap.
                    val collector = CompareRegionAccumulator(canvasWidth, canvasHeight)
                    val visualPercent = if (bmp1 != null && bmp2 != null) {
                        compareBitmaps(bmp1, bmp2, null, sensitivity.threshold, collector::add) {
                            comparisonContext.ensureActive()
                        }
                    } else 100f
                    val regions = if (p1 == null || p2 == null) {
                        listOf(CompareRegion(0, 0, canvasWidth, canvasHeight, canvasWidth.toLong() * canvasHeight))
                    } else collector.regions()

                    val label = when {
                        p1 != null && p2 != null -> "Page ${p1 + 1} vs ${p2 + 1}"
                        p1 != null -> "Page ${p1 + 1} (Removed in Doc 2)"
                        p2 != null -> "Page ${p2 + 1} (Added in Doc 2)"
                        else -> "Page ${idx + 1}"
                    }

                    diffs.add(
                        PageDiffResult(
                            pageIndex = idx,
                            diffPercent = visualPercent,
                            textAddedLines = added,
                            textRemovedLines = removed,
                            doc1PageIndex = p1,
                            doc2PageIndex = p2,
                            alignmentLabel = label,
                            geometryChanged = p1 != null && p2 != null && geometryDiffers(geometry1[p1], geometry2[p2]),
                            geometry1 = p1?.let(geometry1::get),
                            geometry2 = p2?.let(geometry2::get),
                            rasterWidth = canvasWidth, rasterHeight = canvasHeight,
                            regions = regions
                        )
                    )
                } finally {
                    bmp1?.recycle()
                    bmp2?.recycle()
                }
            }

            val identical = diffs.count { !it.hasDetectedChanges }
            comparisonContext.ensureActive()
            val summary = DocumentDiffSummary(texts1.size, texts2.size, identical, diffs.size - identical,
                diffs.toList(), sensitivity, ComparisonPreviewSources(first, second, ::releaseInputs))
            completed = true
            return summary
        } finally {
            if (!completed) releaseInputs()
        }
    }

    fun alignPages(texts1: List<String>, texts2: List<String>): List<PageAlignment> =
        alignPagesChecked(texts1, texts2) {}

    private fun requireAlignmentBudget(n: Int, m: Int) {
        require(n in 0..SecurityLimits.MAX_PAGE_COUNT && m in 0..SecurityLimits.MAX_PAGE_COUNT) {
            "Too many pages to compare"
        }
        require((n.toLong() + 1) * (m.toLong() + 1) <= 1_000_000L) {
            "Page alignment exceeds its memory budget; compare smaller page ranges"
        }
    }

    internal fun alignPagesChecked(
        texts1: List<String>, texts2: List<String>,
        fingerprints1: Map<Int, PageFingerprint> = emptyMap(),
        fingerprints2: Map<Int, PageFingerprint> = emptyMap(),
        checkCancellation: () -> Unit = {}
    ): List<PageAlignment> {
        checkCancellation()
        val n = texts1.size
        val m = texts2.size
        requireAlignmentBudget(n, m)

        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return (0 until m).map { PageAlignment(null, it, 0f) }
        if (m == 0) return (0 until n).map { PageAlignment(it, null, 0f) }

        val tokens1 = texts1.map { extractPageTokens(it) }
        val tokens2 = texts2.map { extractPageTokens(it) }

        val simMatrix = Array(n) { i ->
            checkCancellation()
            FloatArray(m) { j ->
                val a = fingerprints1[i]
                val b = fingerprints2[j]
                if (tokens1[i].isEmpty() && tokens2[j].isEmpty() && a != null && b != null)
                    fingerprintSimilarity(a, b)
                else computePageSimilarity(tokens1[i], tokens2[j], texts1[i], texts2[j], i == j)
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

    internal fun geometryDiffers(a: ComparePageGeometry, b: ComparePageGeometry): Boolean {
        // Compare both boxes and offsets in physical points, plus the rotation.
        // 0.01 pt tolerance avoids insignificant PDF float serialization noise.
        val left = listOf(a.mediaX, a.mediaY, a.mediaWidth, a.mediaHeight,
            a.cropX, a.cropY, a.cropWidth, a.cropHeight)
        val right = listOf(b.mediaX, b.mediaY, b.mediaWidth, b.mediaHeight,
            b.cropX, b.cropY, b.cropWidth, b.cropHeight)
        return a.rotation != b.rotation ||
            left.indices.any { kotlin.math.abs(left[it] * a.userUnit - right[it] * b.userUnit) > 0.01 }
    }

    internal data class PageFingerprint(val hash: Long, val luminance: IntArray)

    /** 9 x 8 spatial luminance cells plus 64 horizontal-gradient bits.
     * Similarity is only an alignment heuristic, never proof of equality. */
    internal fun fingerprint(cells: IntArray): PageFingerprint {
        require(cells.size == 72 && cells.all { it in 0..255 })
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            if (cells[y * 9 + x] > cells[y * 9 + x + 1])
                hash = hash or (1L shl (y * 8 + x))
        }
        return PageFingerprint(hash, cells.copyOf())
    }

    internal fun fingerprintSimilarity(a: PageFingerprint, b: PageFingerprint): Float {
        val hamming = java.lang.Long.bitCount(a.hash xor b.hash)
        val meanDifference = a.luminance.indices.sumOf {
            kotlin.math.abs(a.luminance[it] - b.luminance[it])
        } / 72.0
        if (hamming > 12 || meanDifference > 25.0) return 0f
        return (1.0 - 0.5 * hamming / 64.0 - 0.5 * meanDifference / 255.0).toFloat()
    }

    private suspend fun scannedFingerprints(
        context: Context, uri: Uri, texts: List<String>
    ): Map<Int, PageFingerprint> {
        val results = mutableMapOf<Int, PageFingerprint>()
        for ((index, text) in texts.withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (extractPageTokens(text).isNotEmpty()) continue
            val bitmap = requireRenderedPage(index, PdfEditor.renderPageBitmap(context, uri, index, 96))
            try {
                val sums = LongArray(72)
                val counts = IntArray(72)
                val row = IntArray(bitmap.width)
                for (y in 0 until bitmap.height) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    for (x in row.indices) {
                        val color = row[x]
                        val r = (color ushr 16) and 255
                        val g = (color ushr 8) and 255
                        val b = color and 255
                        val cell = (y * 8 / bitmap.height) * 9 + x * 9 / bitmap.width
                        sums[cell] += (299 * r + 587 * g + 114 * b) / 1000
                        counts[cell]++
                    }
                }
                // Tiny pages may leave cells unsampled; use a deterministic nearest sample.
                val cells = IntArray(72) { i ->
                    if (counts[i] > 0) (sums[i] / counts[i]).toInt()
                    else {
                        val color = bitmap.getPixel(
                            ((i % 9 + 0.5) * bitmap.width / 9).toInt().coerceIn(0, bitmap.width - 1),
                            ((i / 9 + 0.5) * bitmap.height / 8).toInt().coerceIn(0, bitmap.height - 1))
                        (299 * ((color ushr 16) and 255) + 587 * ((color ushr 8) and 255) + 114 * (color and 255)) / 1000
                    }
                }
                results[index] = fingerprint(cells)
            } finally { bitmap.recycle() }
        }
        return results
    }

    /** Generate one overlay on demand from the exact same staged inputs and render size. */
    internal suspend fun renderPreview(
        context: Context, first: Uri, second: Uri, page: PageDiffResult, sensitivity: CompareSensitivity
    ): Bitmap {
        val cancellation = kotlinx.coroutines.currentCoroutineContext()
        var a: Bitmap? = null
        var b: Bitmap? = null
        var output: Bitmap? = null
        try {
            a = page.doc1PageIndex?.let { requireRenderedPage(it, PdfEditor.renderPageBitmap(context, first, it, 720)) }
            b = page.doc2PageIndex?.let { requireRenderedPage(it, PdfEditor.renderPageBitmap(context, second, it, 720)) }
            cancellation.ensureActive()
            check(maxOf(a?.width ?: 0, b?.width ?: 0) == page.rasterWidth &&
                maxOf(a?.height ?: 0, b?.height ?: 0) == page.rasterHeight) {
                "Preview raster geometry does not match comparison result"
            }
            output = when {
                a != null && b != null -> {
                    val (score, overlay) = generateVisualDiff(a, b, sensitivity.threshold) { cancellation.ensureActive() }
                    if (score != page.diffPercent) {
                        overlay.recycle()
                        error("Preview does not match comparison result")
                    }
                    overlay
                }
                a != null -> generateSinglePageTinted(a, Color.argb(160, 198, 40, 40))
                b != null -> generateSinglePageTinted(b, Color.argb(160, 46, 125, 50))
                else -> error("Alignment contains no page")
            }
            cancellation.ensureActive()
            val result = checkNotNull(output)
            output = null
            return result
        } finally {
            output?.recycle()
            a?.recycle()
            b?.recycle()
        }
    }

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

    private fun generateVisualDiff(bmp1: Bitmap, bmp2: Bitmap, threshold: Int,
                                   checkCancellation: () -> Unit): Pair<Float, Bitmap> {
        val width = maxOf(bmp1.width, bmp2.width)
        val height = maxOf(bmp1.height, bmp2.height)
        SecurityLimits.requirePixels(width, height)
        val diff = createBitmap(width, height)
        try {
            val percent = compareBitmaps(bmp1, bmp2, diff, threshold, null, checkCancellation)
            return percent to diff
        } catch (failure: Throwable) {
            diff.recycle()
            throw failure
        }
    }

    private fun compareBitmaps(a: Bitmap, b: Bitmap, overlay: Bitmap?, threshold: Int,
                               onChanged: ((Int, Int) -> Unit)?, checkCancellation: () -> Unit): Float =
        compareRasterRows(a.width, a.height, b.width, b.height,
            read1 = { y, row -> a.getPixels(row, 0, a.width, 0, y, a.width, 1) },
            read2 = { y, row -> b.getPixels(row, 0, b.width, 0, y, b.width, 1) },
            write = overlay?.let { bitmap ->
                { y: Int, row: IntArray -> bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1) }
            },
            checkCancellation = checkCancellation, threshold = threshold, onChanged = onChanged)

    /**
     * Compare the union of both rendered page areas. Pixels outside both pages
     * are not counted. Non-overlapping page regions are always differences.
     * Row buffers keep working memory proportional to width rather than area.
     */
    internal fun compareRasterRows(
        width1: Int, height1: Int, width2: Int, height2: Int,
        read1: (Int, IntArray) -> Unit,
        read2: (Int, IntArray) -> Unit,
        write: ((Int, IntArray) -> Unit)? = null,
        checkCancellation: () -> Unit = {},
        threshold: Int = CompareSensitivity.BALANCED.threshold,
        onChanged: ((Int, Int) -> Unit)? = null
    ): Float {
        require(threshold in 0..255)
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
                    onChanged?.invoke(x, y)
                    out[x] = if (in2) 0xA02E7D32.toInt() else 0xA0C62828.toInt()
                    continue
                }
                val c1 = row1[x]
                val c2 = row2[x]
                val r = (c2 ushr 16) and 255
                val g = (c2 ushr 8) and 255
                val b = c2 and 255
                val different = kotlin.math.abs(((c1 ushr 16) and 255) - r) > threshold ||
                    kotlin.math.abs(((c1 ushr 8) and 255) - g) > threshold ||
                    kotlin.math.abs((c1 and 255) - b) > threshold
                if (different) {
                    changed++
                    onChanged?.invoke(x, y)
                    out[x] = 0xE6EB1E64.toInt()
                } else {
                    val gray = (r + g + b) / 3
                    out[x] = (120 shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
            write?.invoke(y, out)
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
