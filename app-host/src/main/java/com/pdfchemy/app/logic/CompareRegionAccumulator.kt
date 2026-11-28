package com.pdfchemy.app.logic

import com.pdfchemy.app.security.SecurityLimits

/** Pixel coordinates in the union raster, with exclusive right and bottom bounds. */
data class CompareRegion(
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val changedPixels: Long
)

/**
 * Group changed pixels using four-connected occupied tiles in a fixed 16 x 16 grid.
 * Bounds are the actual changed pixel extrema, not the tile boundaries.
 * This is a bounded spatial grouping heuristic, not semantic object detection.
 */
internal class CompareRegionAccumulator(private val width: Int, private val height: Int) {
    init { SecurityLimits.requirePixels(width, height) }

    private val columns = minOf(16, width)
    private val rows = minOf(16, height)
    private val count = columns * rows
    private val pixels = LongArray(count)
    private val left = IntArray(count) { width }
    private val top = IntArray(count) { height }
    private val right = IntArray(count)
    private val bottom = IntArray(count)

    fun add(x: Int, y: Int) {
        require(x in 0 until width && y in 0 until height)
        val i = (y * rows / height) * columns + x * columns / width
        pixels[i]++
        left[i] = minOf(left[i], x)
        top[i] = minOf(top[i], y)
        right[i] = maxOf(right[i], x + 1)
        bottom[i] = maxOf(bottom[i], y + 1)
    }

    fun regions(): List<CompareRegion> {
        val visited = BooleanArray(count)
        val queue = IntArray(count)
        val result = mutableListOf<CompareRegion>()
        for (start in 0 until count) {
            if (visited[start] || pixels[start] == 0L) continue
            var head = 0
            var tail = 0
            fun enqueue(i: Int) {
                if (!visited[i] && pixels[i] != 0L) {
                    visited[i] = true
                    queue[tail++] = i
                }
            }
            enqueue(start)
            var l = width
            var t = height
            var r = 0
            var b = 0
            var changed = 0L
            while (head < tail) {
                val i = queue[head++]
                l = minOf(l, left[i]); t = minOf(t, top[i])
                r = maxOf(r, right[i]); b = maxOf(b, bottom[i])
                changed += pixels[i]
                val x = i % columns
                val y = i / columns
                if (x > 0) enqueue(i - 1)
                if (x + 1 < columns) enqueue(i + 1)
                if (y > 0) enqueue(i - columns)
                if (y + 1 < rows) enqueue(i + columns)
            }
            result.add(CompareRegion(l, t, r, b, changed))
        }
        return result.sortedWith(compareBy<CompareRegion> { it.top }.thenBy { it.left })
    }
}

/** Threshold is per RGB channel; geometry and text are always compared independently. */
enum class CompareSensitivity(val threshold: Int) {
    STRICT(0), BALANCED(30), RELAXED(60)
}
