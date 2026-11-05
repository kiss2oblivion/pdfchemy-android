package com.pdfchemy.app.billing

import android.app.Activity
import android.os.SystemClock
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.ump.ConsentInformation
import com.google.android.ump.UserMessagingPlatform
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdMonotonicClockTest {
    @After fun cleanup() { AdManager.resetForTesting(); unmockkAll() }

    @Test fun defaultClockAndResetUseElapsedTimeRatherThanWallTime() {
        mockkStatic(SystemClock::class)
        var elapsed = 12_345L
        every { SystemClock.elapsedRealtime() } answers { elapsed }
        AdManager.resetForTesting()
        assertEquals(elapsed, AdManager.clock())
        // Advance the shadow wall clock while holding the elapsed source fixed.
        ShadowSystemClock.advanceBy(Duration.ofHours(2))
        assertEquals(elapsed, AdManager.clock())
        elapsed += 30_000L
        assertEquals(elapsed, AdManager.clock())
        AdManager.clock = { -1L }
        AdManager.resetForTesting()
        assertEquals(elapsed, AdManager.clock())
    }

    private fun withActivity(block: (Activity) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val consent = mockk<ConsentInformation>()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true
        every { InterstitialAd.load(any(), any(), any(), any()) } just Runs
        AdManager.resetForTesting()
        try { block(controller.get()) } finally { controller.pause().stop().destroy() }
    }

    @Test fun wallClockAdvanceCannotBypassCooldownButSixtyElapsedSecondsCan() = withActivity { activity ->
        mockkStatic(SystemClock::class)
        var elapsed = 100_000L
        every { SystemClock.elapsedRealtime() } answers { elapsed }
        val first = mockk<InterstitialAd>(relaxed = true)
        val next = mockk<InterstitialAd>(relaxed = true)
        AdManager.setLoadedAdForTesting(first)
        AdManager.showInterstitialIfReady(activity, false) {}
        verify(exactly = 1) { first.show(activity) }
        AdManager.setLoadedAdForTesting(next)
        ShadowSystemClock.advanceBy(Duration.ofDays(1))
        elapsed += 30_000L
        AdManager.showInterstitialIfReady(activity, false) {}
        verify(exactly = 0) { next.show(activity) }
        elapsed += 29_999L
        AdManager.showInterstitialIfReady(activity, false) {}
        verify(exactly = 0) { next.show(activity) }
        elapsed += 1L
        AdManager.showInterstitialIfReady(activity, false) {}
        verify(exactly = 1) { next.show(activity) }
    }

    @Test fun firstEligibleAdCanShowAtElapsedTimeZero() = withActivity { activity ->
        AdManager.clock = { 0L }
        val ad = mockk<InterstitialAd>(relaxed = true)
        AdManager.setLoadedAdForTesting(ad)
        AdManager.showInterstitialIfReady(activity, false) {}
        verify(exactly = 1) { ad.show(activity) }
        assertEquals(0L, AdManager.debugCounters.skippedCooldown)
    }

    @Test fun adLoadedAtElapsedTimeZeroExpiresAtFiftyFiveMinutes() {
        AdManager.resetForTesting()
        var elapsed = 0L
        AdManager.clock = { elapsed }
        AdManager.setLoadedAdForTesting(mockk(relaxed = true))
        elapsed = AdManager.AD_EXPIRATION_MILLIS - 1
        assertTrue(AdManager.isAdLoaded())
        elapsed++
        assertFalse(AdManager.isAdLoaded())
        assertEquals(1L, AdManager.debugCounters.expired)
    }
}
