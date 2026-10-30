package com.pdfchemy.app.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.BoundedPageBitmapCache
import com.pdfchemy.app.logic.ReaderAccessibilityLayout
import com.pdfchemy.app.logic.ReaderPageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderAccessibilityAndResilienceTest {

    @Test
    fun `adaptive cache budget scales down on low RAM or constrained heaps`() {
        val (lowRamEntries, lowRamBytes) = BoundedPageBitmapCache.computeAdaptiveBudget(
            maxMemoryBytes = 256L * 1024L * 1024L,
            isLowRamDevice = true
        )
        assertEquals(3, lowRamEntries)
        assertEquals(12L * 1024L * 1024L, lowRamBytes)

        val (smallHeapEntries, smallHeapBytes) = BoundedPageBitmapCache.computeAdaptiveBudget(
            maxMemoryBytes = 96L * 1024L * 1024L,
            isLowRamDevice = false
        )
        assertEquals(3, smallHeapEntries)
        assertEquals(12L * 1024L * 1024L, smallHeapBytes)

        val (standardEntries, standardBytes) = BoundedPageBitmapCache.computeAdaptiveBudget(
            maxMemoryBytes = 512L * 1024L * 1024L,
            isLowRamDevice = false
        )
        assertEquals(6, standardEntries)
        assertEquals(32L * 1024L * 1024L, standardBytes)
    }

    @Test
    fun `onTrimMemory evicts unreferenced bitmaps while protecting active displayed pages`() {
        val cache = BoundedPageBitmapCache(maxEntries = 6, maxBytes = 32L * 1024L * 1024L)
        val key0 = ReaderPageKey("file:///doc.pdf", 0, 1080)
        val key1 = ReaderPageKey("file:///doc.pdf", 1, 1080)

        val bmp0 = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        val bmp1 = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)

        cache.putAndAcquire(key0, bmp0)
        cache.putAndAcquire(key1, bmp1)

        // Page 0 scrolls off-screen and releases its reference; Page 1 remains visible
        cache.release(bmp0)
        assertEquals(0, cache.activeReferenceCount(bmp0))
        assertEquals(1, cache.activeReferenceCount(bmp1))

        // Running-low memory pressure trims unreferenced page 0 but preserves visible page 1
        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertTrue(bmp0.isRecycled)
        assertFalse(bmp1.isRecycled)
        assertEquals(1, cache.cachedEntryCount())

        // Critical memory pressure clears cache entry for page 1 but defers recycling until composable release
        cache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(0, cache.cachedEntryCount())
        assertFalse(bmp1.isRecycled)

        cache.release(bmp1)
        assertTrue(bmp1.isRecycled)
    }

    @Test
    fun `bar layout enforces 48dp touch targets and adapts to small screens, landscape, and 200 percent font scale`() {
        val standardSpec = ReaderAccessibilityLayout.computeBarLayout(
            screenWidthDp = 411,
            isLandscape = false,
            fontScale = 1.0f
        )
        assertEquals(48, standardSpec.minTouchTargetDp)
        assertFalse(standardSpec.compactLandscape)
        assertFalse(standardSpec.useHorizontalScrollForBottomActions)

        val smallScreenSpec = ReaderAccessibilityLayout.computeBarLayout(
            screenWidthDp = 320,
            isLandscape = false,
            fontScale = 1.0f
        )
        assertEquals(48, smallScreenSpec.minTouchTargetDp)
        assertEquals(8, smallScreenSpec.horizontalPaddingDp)
        assertTrue(smallScreenSpec.useHorizontalScrollForBottomActions)

        val largeFontSpec = ReaderAccessibilityLayout.computeBarLayout(
            screenWidthDp = 411,
            isLandscape = false,
            fontScale = 2.0f
        )
        assertEquals(48, largeFontSpec.minTouchTargetDp)
        assertTrue(largeFontSpec.useHorizontalScrollForBottomActions)

        val landscapeSpec = ReaderAccessibilityLayout.computeBarLayout(
            screenWidthDp = 640,
            isLandscape = true,
            fontScale = 1.0f
        )
        assertTrue(landscapeSpec.compactLandscape)
        assertEquals(6, landscapeSpec.verticalPaddingDp)
        assertTrue(landscapeSpec.useHorizontalScrollForBottomActions)
    }

    @Test
    fun `all Reader localized strings and format specifiers resolve cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.getString(R.string.reader_action_search).isNotBlank())
        assertTrue(context.getString(R.string.reader_toc_title).isNotBlank())
        assertTrue(context.getString(R.string.reader_fit_width).isNotBlank())
        assertTrue(context.getString(R.string.reader_fit_page).isNotBlank())
        assertTrue(context.getString(R.string.reader_search_hint).isNotBlank())
        assertTrue(context.getString(R.string.reader_action_clear_search).isNotBlank())
        assertTrue(context.getString(R.string.reader_search_no_matches).isNotBlank())
        assertTrue(context.getString(R.string.page_render_failed).isNotBlank())
        assertTrue(context.getString(R.string.retry).isNotBlank())

        assertEquals("Page 3 of 12", context.getString(R.string.reader_page_indicator, 3, 12))
        assertEquals("3 / 12", context.getString(R.string.reader_page_badge_compact, 3, 12))
        assertEquals("Page 5", context.getString(R.string.reader_page_content_desc, 5))
        assertEquals("2.5x • Reset", context.getString(R.string.reader_reset_zoom_fmt, 2.5f))
        assertTrue(context.getString(R.string.previous_match).isNotBlank())
        assertTrue(context.getString(R.string.next_match).isNotBlank())

        val supportedTags = listOf(
            "ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko", "nl",
            "pl", "pt", "pt-BR", "ro", "ru", "th", "tr", "vi", "zh-CN", "zh-TW"
        )
        for (tag in supportedTags) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(locale)
            val localizedContext = context.createConfigurationContext(config)
            assertTrue("Missing menu_more_tools in $tag", localizedContext.getString(R.string.menu_more_tools).isNotBlank())
            assertTrue("Missing reader_action_search in $tag", localizedContext.getString(R.string.reader_action_search).isNotBlank())
            assertTrue("Missing reader_toc_title in $tag", localizedContext.getString(R.string.reader_toc_title).isNotBlank())
            assertTrue("Missing reader_fit_width in $tag", localizedContext.getString(R.string.reader_fit_width).isNotBlank())
            assertTrue("Missing reader_fit_page in $tag", localizedContext.getString(R.string.reader_fit_page).isNotBlank())
            assertTrue("Missing reader_search_hint in $tag", localizedContext.getString(R.string.reader_search_hint).isNotBlank())
            assertTrue("Missing reader_action_clear_search in $tag", localizedContext.getString(R.string.reader_action_clear_search).isNotBlank())
            assertTrue("Missing reader_search_no_matches in $tag", localizedContext.getString(R.string.reader_search_no_matches).isNotBlank())
            assertTrue("Missing reader_page_indicator in $tag", localizedContext.getString(R.string.reader_page_indicator, 3, 12).isNotBlank())
            assertTrue("Missing reader_page_badge_compact in $tag", localizedContext.getString(R.string.reader_page_badge_compact, 3, 12).isNotBlank())
            assertTrue("Missing reader_page_content_desc in $tag", localizedContext.getString(R.string.reader_page_content_desc, 5).isNotBlank())
            assertTrue("Missing reader_reset_zoom_fmt in $tag", localizedContext.getString(R.string.reader_reset_zoom_fmt, 2.5f).isNotBlank())
            assertTrue("Missing previous_match in $tag", localizedContext.getString(R.string.previous_match).isNotBlank())
            assertTrue("Missing next_match in $tag", localizedContext.getString(R.string.next_match).isNotBlank())
        }
    }
}

