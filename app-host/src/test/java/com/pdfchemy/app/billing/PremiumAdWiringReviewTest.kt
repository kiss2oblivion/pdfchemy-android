package com.pdfchemy.app.billing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.MainActivity
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.UserMessagingPlatform
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PremiumAdWiringReviewTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun cleanup() {
        AdManager.resetForTesting()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun actualStartupPathRechecksPremiumAtLoadCompletion() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = spyk(controller.get())
        var premium = false
        every { activity.isUserEntitledToPremium() } answers { premium }
        val consent = mockk<ConsentInformation>()
        val callbacks = mutableListOf<InterstitialAdLoadCallback>()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(MobileAds::class)
        mockkStatic(InterstitialAd::class)
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true
        every { MobileAds.initialize(any(), any<OnInitializationCompleteListener>()) } answers {
            secondArg<OnInitializationCompleteListener>().onInitializationComplete(mockk(relaxed = true))
        }
        every { InterstitialAd.load(any(), any(), any(), capture(callbacks)) } just Runs

        AdManager.resetForTesting()
        activity.initializeMobileAdsIfPermitted()
        assertEquals(1, callbacks.size)

        // Entitlement transition occurs while ad was in flight
        premium = true
        callbacks.single().onAdLoaded(mockk(relaxed = true))
        assertFalse("The production startup call must discard ads after an entitlement transition", AdManager.isAdLoaded())
    }

    @Test
    fun activityRecreationPreservesLoadedAdWhenNotFinishing() {
        val dummyAd = mockk<InterstitialAd>(relaxed = true)
        val callbacks = mutableListOf<InterstitialAdLoadCallback>()
        mockkStatic(InterstitialAd::class)
        mockkStatic(MobileAds::class)
        mockkStatic(UserMessagingPlatform::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true
        every { consent.requestConsentInfoUpdate(any(), any(), any(), any()) } just Runs
        every { MobileAds.initialize(any(), any<OnInitializationCompleteListener>()) } answers {
            secondArg<OnInitializationCompleteListener>().onInitializationComplete(mockk(relaxed = true))
        }
        every { InterstitialAd.load(any(), any(), any(), capture(callbacks)) } just Runs

        AdManager.resetForTesting()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(1, callbacks.size)
        callbacks.single().onAdLoaded(dummyAd)
        assertTrue("Preloaded ad should be loaded and ready", AdManager.isAdLoaded())

        // Recreate activity (simulating rotation or theme toggle without finishing)
        controller.recreate()
        assertTrue("Activity recreation must not clear the preloaded ad state", AdManager.isAdLoaded())

        // Finish activity completely (app exit)
        controller.get().finish()
        controller.destroy()
        assertFalse("Actual finishing onDestroy must clear the ad", AdManager.isAdLoaded())
    }

    @Test
    fun premiumActivationInvalidatesPendingLoadedAdsImmediately() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dummyAd = mockk<InterstitialAd>(relaxed = true)
        val callbacks = mutableListOf<InterstitialAdLoadCallback>()
        mockkStatic(InterstitialAd::class)
        every { InterstitialAd.load(any(), any(), any(), capture(callbacks)) } just Runs
        val consent = mockk<ConsentInformation>()
        mockkStatic(UserMessagingPlatform::class)
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true

        var isPremium = false
        AdManager.resetForTesting()
        AdManager.loadInterstitial(context, isPremium = isPremium, isPremiumProvider = { isPremium })
        assertEquals(1, callbacks.size)
        callbacks.single().onAdLoaded(dummyAd)
        assertTrue(AdManager.isAdLoaded())

        // User buys premium
        isPremium = true
        AdManager.clearAd()
        assertFalse("Premium activation must purge loaded ad immediately", AdManager.isAdLoaded())

        // Attempting to show or load while premium must be rejected
        var callbackInvoked = false
        val activity = mockk<android.app.Activity>(relaxed = true)
        every { activity.isFinishing } returns false
        every { activity.isDestroyed } returns false
        AdManager.showInterstitialIfReady(activity, isPremium = true) {
            callbackInvoked = true
        }
        assertTrue("showInterstitialIfReady must complete immediately without showing ad to premium user", callbackInvoked)
        assertFalse(AdManager.isAdLoaded())
    }
}
