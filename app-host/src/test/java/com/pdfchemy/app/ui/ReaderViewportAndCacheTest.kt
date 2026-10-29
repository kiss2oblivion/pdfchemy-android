package com.pdfchemy.app.ui

import android.graphics.Bitmap
import com.pdfchemy.app.logic.BoundedPageBitmapCache
import com.pdfchemy.app.logic.ReaderPageKey
import com.pdfchemy.app.logic.ReaderRenderGate
import com.pdfchemy.app.logic.ReaderZoomCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderViewportAndCacheTest {

    @Test
    fun boundedCacheReusesBitmapsOnScrollBackWithoutRecycling() {
        val cache = BoundedPageBitmapCache(maxEntries = 4, maxBytes = 16L * 1024L * 1024L)
        val key0 = ReaderPageKey("file:///staged/doc.pdf", pageIndex = 0, targetWidth = 1080)
        val key1 = ReaderPageKey("file:///staged/doc.pdf", pageIndex = 1, targetWidth = 1080)

        val bmp0 = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888)
        val bmp1 = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888)

        cache.putAndAcquire(key0, bmp0)
        cache.putAndAcquire(key1, bmp1)
        assertEquals(2, cache.cachedEntryCount())
        assertEquals(1, cache.activeReferenceCount(bmp0))

        // Simulate page 0 scrolling off-screen
        cache.release(bmp0)
        assertEquals(0, cache.activeReferenceCount(bmp0))
        assertFalse("Bitmap within LRU budget must not be recycled when scrolling slightly off-screen", bmp0.isRecycled)

        // Simulate scrolling back to page 0
        val reacquired = cache.acquire(key0)
        assertSame(bmp0, reacquired)
        assertEquals(1, cache.hitCount)
        assertEquals(1, cache.activeReferenceCount(bmp0))
    }

    @Test
    fun boundedCacheEvictsLruAndDefersRecyclingUntilActiveReferenceReleased() {
        val cache = BoundedPageBitmapCache(maxEntries = 2, maxBytes = 16L * 1024L * 1024L)
        val key0 = ReaderPageKey("file:///staged/doc.pdf", pageIndex = 0, targetWidth = 1080)
        val key1 = ReaderPageKey("file:///staged/doc.pdf", pageIndex = 1, targetWidth = 1080)
        val key2 = ReaderPageKey("file:///staged/doc.pdf", pageIndex = 2, targetWidth = 1080)

        val bmp0 = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888)
        val bmp1 = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888)

        // Page 0 stays actively referenced; page 1 is released before page 2 arrives
        cache.putAndAcquire(key0, bmp0)
        cache.putAndAcquire(key1, bmp1)
        cache.release(bmp1)

        // Inserting page 2 exceeds maxEntries=2; key0 is oldest in LRU order
        cache.putAndAcquire(key2, bmp2)
        assertEquals(2, cache.cachedEntryCount())
        assertEquals(1, cache.evictionCount)

        // Because bmp0 still had activeReferenceCount == 1, it must NOT be recycled until released
        assertFalse("Evicted bitmap must not be recycled while still bound to an active Composable", bmp0.isRecycled)
        cache.release(bmp0)
        assertTrue("Evicted bitmap must be recycled immediately once its active reference is released", bmp0.isRecycled)

        // Clearing cache recycles unreferenced bmp1 and marks referenced bmp2 for recycle on release
        cache.clear()
        assertTrue(bmp1.isRecycled)
        assertFalse(bmp2.isRecycled)
        cache.release(bmp2)
        assertTrue(bmp2.isRecycled)
    }

    @Test
    fun renderGateRejectsStaleTokensAndCancelsOnReaderExit() {
        val gate = ReaderRenderGate()
        val firstToken = gate.beginRender(pageIndex = 3)
        assertTrue(gate.isCurrent(pageIndex = 3, token = firstToken))

        // Rapid re-request for page 3 supersedes firstToken
        val secondToken = gate.beginRender(pageIndex = 3)
        assertFalse(gate.isCurrent(pageIndex = 3, token = firstToken))
        assertTrue(gate.isCurrent(pageIndex = 3, token = secondToken))

        // Navigating away from Reader closes the gate
        gate.close()
        assertTrue(gate.isClosed())
        assertFalse(gate.isCurrent(pageIndex = 3, token = secondToken))
        assertEquals(-1L, gate.beginRender(pageIndex = 4))
    }

    @Test
    fun zoomCalculatorEnablesNativeInertialScrollAt1xAndClampsZoomPanAndOverflow() {
        assertTrue(ReaderZoomCalculator.isNativeScrollEnabled(scale = 1.0f, activePointerCount = 1))
        assertFalse(ReaderZoomCalculator.isNativeScrollEnabled(scale = 1.0f, activePointerCount = 2))
        assertFalse(ReaderZoomCalculator.isNativeScrollEnabled(scale = 2.0f, activePointerCount = 1))

        // Double-tap zooms in toward tap focal point, second double-tap resets to 1x
        val zoomedIn = ReaderZoomCalculator.toggleDoubleTapZoom(
            currentScale = 1f,
            tapX = 200f,
            tapY = 300f,
            viewportWidth = 1000f,
            viewportHeight = 1600f
        )
        assertEquals(ReaderZoomCalculator.DOUBLE_TAP_SCALE, zoomedIn.scale, 0.001f)
        assertTrue(zoomedIn.offsetX > 0f)
        assertTrue(zoomedIn.offsetY > 0f)

        val resetZoom = ReaderZoomCalculator.toggleDoubleTapZoom(
            currentScale = zoomedIn.scale,
            tapX = 200f,
            tapY = 300f,
            viewportWidth = 1000f,
            viewportHeight = 1600f
        )
        assertEquals(1f, resetZoom.scale, 0.001f)
        assertEquals(0f, resetZoom.offsetX, 0.001f)
        assertEquals(0f, resetZoom.offsetY, 0.001f)

        // Vertical pan overflow beyond zoomed viewport edge produces verticalScrollDelta for continuous page scrolling
        val panAtBoundary = ReaderZoomCalculator.applyGesture(
            currentScale = 2f,
            currentOffsetX = 0f,
            currentOffsetY = -800f, // already at bottom edge for 1600px viewport at 2x
            zoomChange = 1f,
            panX = 0f,
            panY = -120f, // dragging upward to scroll down to next page
            viewportWidth = 1000f,
            viewportHeight = 1600f
        )
        assertEquals(-800f, panAtBoundary.offsetY, 0.001f)
        assertEquals(60f, panAtBoundary.verticalScrollDelta, 0.001f)
    }
}
