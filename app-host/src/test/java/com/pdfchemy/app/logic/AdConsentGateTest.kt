package com.pdfchemy.app.logic

import android.app.Activity
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.ump.ConsentInformation
import com.google.android.ump.UserMessagingPlatform
import com.pdfchemy.app.billing.AdManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdConsentGateTest {
    @After fun cleanup() { unmockkAll() }

    @Test fun cannotRequestAdsPreventsLoadingAndDoesNotBlockDocumentCompletion() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns false
        AdManager.loadInterstitial(activity)
        verify(exactly = 0) { InterstitialAd.load(any(), any(), any(), any()) }
        var completed = false
        AdManager.showInterstitialIfReady(activity, false) { completed = true }
        assertTrue(completed)
        activity.finish()
    }
}
