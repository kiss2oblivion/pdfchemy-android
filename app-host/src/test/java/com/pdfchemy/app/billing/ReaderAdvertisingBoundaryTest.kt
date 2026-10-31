package com.pdfchemy.app.billing

import com.pdfchemy.app.Screen
import com.pdfchemy.app.shouldShowBannerAd
import org.junit.Assert.*
import org.junit.Test

/** The banner gate called by MainApp; these tests never request an actual ad. */
class ReaderAdvertisingBoundaryTest {
    private fun assertProtected(screen: Screen) {
        for (premium in listOf(false, true)) {
            for (consented in listOf(false, true)) {
                for (screenshot in listOf(false, true)) {
                    assertFalse("Reading must remain ad-free: $premium/$consented/$screenshot",
                        shouldShowBannerAd(screen, premium, consented, screenshot))
                }
            }
        }
    }

    @Test fun readerNeverHostsBanner() = assertProtected(Screen.PdfReader())
    @Test fun reflowNeverHostsBanner() = assertProtected(Screen.ReflowReader())
    @Test fun eligibleFreeHomeCanHostBanner() {
        assertTrue(shouldShowBannerAd(Screen.Home, false, true, false))
    }
    @Test fun premiumConsentAndScreenshotRestrictionsRemain() {
        assertFalse(shouldShowBannerAd(Screen.Home, true, true, false))
        assertFalse(shouldShowBannerAd(Screen.Home, false, false, false))
        assertFalse(shouldShowBannerAd(Screen.Home, false, true, true))
    }
}
