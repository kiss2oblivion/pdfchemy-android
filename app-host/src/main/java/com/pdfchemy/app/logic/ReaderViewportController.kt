package com.pdfchemy.app.logic

import android.graphics.Bitmap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class ReaderPageKey(
    val uriString: String,
    val pageIndex: Int,
    val targetWidth: Int,
    val fitPage: Boolean = false
)

data class ZoomTransformResult(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val verticalScrollDelta: Float = 0f
)

/**
 * Bounded, reference-counted LRU bitmap cache for PDF Reader pages.
 * Prevents redundant IPC page re-rendering during continuous scrolling while
 * guaranteeing that bitmaps are never recycled while still bound to an active
 * Composable and are deterministically released on eviction or reader exit.
 */
class BoundedPageBitmapCache(
    private val maxEntries: Int = 6,
    private val maxBytes: Long = 32L * 1024L * 1024L
) {
    private data class CacheEntry(
        val key: ReaderPageKey,
        val bitmap: Bitmap,
        val byteCount: Long
    )

    private val entries = LinkedHashMap<ReaderPageKey, CacheEntry>(maxEntries + 2, 0.75f, true)
    private val refCounts = HashMap<Bitmap, Int>()
    private val pendingRecycle = HashSet<Bitmap>()

    var hitCount: Int = 0
        private set
    var missCount: Int = 0
        private set
    var evictionCount: Int = 0
        private set

    @Synchronized
    fun acquire(key: ReaderPageKey): Bitmap? {
        val entry = entries[key]
        if (entry == null || entry.bitmap.isRecycled) {
            if (entry != null) {
                entries.remove(key)
            }
            missCount++
            return null
        }
        hitCount++
        refCounts[entry.bitmap] = (refCounts[entry.bitmap] ?: 0) + 1
        return entry.bitmap
    }

    @Synchronized
    fun putAndAcquire(key: ReaderPageKey, bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap

        val existing = entries.remove(key)
        if (existing != null && existing.bitmap !== bitmap) {
            retireBitmap(existing.bitmap)
        }

        val byteCount = bitmap.allocationByteCount.toLong().coerceAtLeast(1L)
        val newEntry = CacheEntry(key, bitmap, byteCount)
        entries[key] = newEntry
        pendingRecycle.remove(bitmap)
        refCounts[bitmap] = (refCounts[bitmap] ?: 0) + 1

        trimToBudget(protectedKey = key)
        return bitmap
    }

    @Synchronized
    fun release(bitmap: Bitmap?) {
        if (bitmap == null) return
        val currentRefs = (refCounts[bitmap] ?: 0) - 1
        if (currentRefs <= 0) {
            refCounts.remove(bitmap)
            if (pendingRecycle.remove(bitmap)) {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } else {
            refCounts[bitmap] = currentRefs
        }
    }

    @Synchronized
    fun clear() {
        val allEntries = entries.values.toList()
        entries.clear()
        for (entry in allEntries) {
            retireBitmap(entry.bitmap)
        }
    }

    @Synchronized
    fun cachedEntryCount(): Int = entries.size

    @Synchronized
    fun totalBytes(): Long = entries.values.sumOf { it.byteCount }

    @Synchronized
    fun activeReferenceCount(bitmap: Bitmap): Int = refCounts[bitmap] ?: 0

    private fun trimToBudget(protectedKey: ReaderPageKey? = null) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size > maxEntries || totalBytes() > maxBytes)) {
            val candidate = iterator.next()
            if (candidate.key == protectedKey && entries.size == 1) {
                break
            }
            iterator.remove()
            evictionCount++
            retireBitmap(candidate.value.bitmap)
        }
    }

    private fun retireBitmap(bitmap: Bitmap) {
        val refs = refCounts[bitmap] ?: 0
        if (refs <= 0) {
            refCounts.remove(bitmap)
            pendingRecycle.remove(bitmap)
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        } else {
            pendingRecycle.add(bitmap)
        }
    }
}

/**
 * Coordinates render request tokens per page and tracks reader lifecycle closure
 * so stale/in-flight renders cannot overwrite current pages or survive navigation exit.
 */
class ReaderRenderGate {
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)
    private val latestPageToken = HashMap<Int, Long>()

    @Synchronized
    fun beginRender(pageIndex: Int): Long {
        if (closed.get()) return -1L
        val token = sequence.incrementAndGet()
        latestPageToken[pageIndex] = token
        return token
    }

    @Synchronized
    fun isCurrent(pageIndex: Int, token: Long): Boolean {
        if (closed.get() || token <= 0L) return false
        return latestPageToken[pageIndex] == token
    }

    @Synchronized
    fun cancelPage(pageIndex: Int) {
        latestPageToken.remove(pageIndex)
    }

    @Synchronized
    fun close() {
        closed.set(true)
        latestPageToken.clear()
    }

    fun isClosed(): Boolean = closed.get()
}

/**
 * Pure zoom and pan state calculator for continuous PDF reading.
 */
object ReaderZoomCalculator {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f
    private const val SNAP_EPSILON = 1.02f

    fun isZoomed(scale: Float): Boolean = scale > SNAP_EPSILON

    fun isNativeScrollEnabled(scale: Float, activePointerCount: Int = 1): Boolean {
        return !isZoomed(scale) && activePointerCount <= 1
    }

    fun applyGesture(
        currentScale: Float,
        currentOffsetX: Float,
        currentOffsetY: Float,
        zoomChange: Float,
        panX: Float,
        panY: Float,
        viewportWidth: Float,
        viewportHeight: Float
    ): ZoomTransformResult {
        val rawScale = (currentScale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val effectiveScale = if (rawScale <= SNAP_EPSILON && zoomChange <= 1f) MIN_SCALE else rawScale

        if (effectiveScale == MIN_SCALE) {
            return ZoomTransformResult(
                scale = MIN_SCALE,
                offsetX = 0f,
                offsetY = 0f,
                verticalScrollDelta = if (abs(zoomChange - 1f) < 0.001f) -panY else 0f
            )
        }

        val maxOffsetX = ((viewportWidth * effectiveScale) - viewportWidth).coerceAtLeast(0f) / 2f
        val maxOffsetY = ((viewportHeight * effectiveScale) - viewportHeight).coerceAtLeast(0f) / 2f

        val unclampedX = currentOffsetX + panX
        val unclampedY = currentOffsetY + panY
        val clampedX = unclampedX.coerceIn(-maxOffsetX, maxOffsetX)
        val clampedY = unclampedY.coerceIn(-maxOffsetY, maxOffsetY)

        // Any vertical pan overflow beyond the zoomed canvas edge scrolls the document list smoothly
        val overflowY = unclampedY - clampedY
        val verticalScrollDelta = if (abs(overflowY) > 0.5f) -overflowY / effectiveScale else 0f

        return ZoomTransformResult(
            scale = effectiveScale,
            offsetX = clampedX,
            offsetY = clampedY,
            verticalScrollDelta = verticalScrollDelta
        )
    }

    fun toggleDoubleTapZoom(
        currentScale: Float,
        tapX: Float,
        tapY: Float,
        viewportWidth: Float,
        viewportHeight: Float
    ): ZoomTransformResult {
        if (isZoomed(currentScale)) {
            return ZoomTransformResult(scale = MIN_SCALE, offsetX = 0f, offsetY = 0f)
        }
        val targetScale = DOUBLE_TAP_SCALE
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        val desiredOffsetX = (centerX - tapX) * (targetScale - 1f)
        val desiredOffsetY = (centerY - tapY) * (targetScale - 1f)
        val maxOffsetX = ((viewportWidth * targetScale) - viewportWidth).coerceAtLeast(0f) / 2f
        val maxOffsetY = ((viewportHeight * targetScale) - viewportHeight).coerceAtLeast(0f) / 2f
        return ZoomTransformResult(
            scale = targetScale,
            offsetX = desiredOffsetX.coerceIn(-maxOffsetX, maxOffsetX),
            offsetY = desiredOffsetY.coerceIn(-maxOffsetY, maxOffsetY)
        )
    }
}

