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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PremiumAdWiringReviewTest {

    @Before
    fun setup() {
        AdManager.resetForTesting()
        BillingManager.resetCacheForTesting()
    }

    @After
    fun cleanup() {
        AdManager.resetForTesting()
        BillingManager.resetCacheForTesting()
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
        AdManager.setEntitlementSource { premium }
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
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        activity.initializeMobileAdsIfPermitted()
        assertEquals(1, callbacks.size)
        callbacks.single().onAdLoaded(dummyAd)
        assertTrue("Preloaded ad should be loaded and ready", AdManager.isAdLoaded())

        // Non-finishing destroy (simulating configuration change / recreation where isFinishing is false)
        assertFalse("Initial activity should not be finishing", activity.isFinishing)
        controller.pause().stop().destroy()
        assertTrue("Activity destruction without finishing must not clear the preloaded ad state", AdManager.isAdLoaded())

        // Activity 2: Finish activity completely (app exit where isFinishing is true)
        val controller2 = Robolectric.buildActivity(MainActivity::class.java).create()
        controller2.get().finish()
        assertTrue("Finished activity must report isFinishing = true", controller2.get().isFinishing)
        controller2.pause().stop().destroy()
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
        AdManager.setEntitlementSource { isPremium }
        AdManager.loadInterstitial(context, isPremium = isPremium)
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

    @Test
    fun adManagerRetainsZeroActivityReferencesAfterStartup() {
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(MobileAds::class)
        mockkStatic(InterstitialAd::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true
        every { consent.requestConsentInfoUpdate(any(), any(), any(), any()) } just Runs
        every { MobileAds.initialize(any(), any<OnInitializationCompleteListener>()) } answers {
            secondArg<OnInitializationCompleteListener>().onInitializationComplete(mockk(relaxed = true))
        }
        every { InterstitialAd.load(any(), any(), any(), any()) } just Runs

        AdManager.resetForTesting()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.initializeMobileAdsIfPermitted()

        // Inspect all declared fields of AdManager singleton
        val fields = AdManager::class.java.declaredFields
        for (field in fields) {
            var value: Any? = null
            try {
                field.isAccessible = true
                value = field.get(AdManager)
            } catch (e: Exception) {
                if (e is ReflectiveOperationException || e is SecurityException || e.javaClass.simpleName == "InaccessibleObjectException") {
                    // Reflection not permitted or accessible, continue
                } else {
                    throw e
                }
            }

            if (value != null) {
                assertNoActivityReferences(value, "AdManager field '${field.name}'")
            }
        }
    }

    @Test
    fun reflectionInspectorDetectsDirectActivityLeak() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = controller.get()
        class DirectLeaker(val captured: Any)
        val leaker = DirectLeaker(activity)

        val error = assertThrows(AssertionError::class.java) {
            assertNoActivityReferences(leaker, "DirectLeaker")
        }
        assertTrue(
            "Assertion failure must indicate that field references an Activity",
            error.message?.contains("must not reference an Activity") == true
        )
        controller.destroy()
    }

    @Test
    fun reflectionInspectorDetectsLambdaCapturedActivity() {
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = controller.get()
        val leakyLambda: () -> String = { activity.packageName }

        val error = assertThrows(AssertionError::class.java) {
            assertNoActivityReferences(leakyLambda, "leakyLambda")
        }
        assertTrue(
            "Assertion failure must indicate that lambda closure captures an Activity",
            error.message?.contains("must not reference an Activity") == true
        )
        controller.destroy()
    }

    @Test
    fun cachedEntitlementRestorationClearsAdsImmediately() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dummyAd = mockk<InterstitialAd>(relaxed = true)
        val callbacks = mutableListOf<InterstitialAdLoadCallback>()
        mockkStatic(InterstitialAd::class)
        mockkStatic(UserMessagingPlatform::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true
        every { InterstitialAd.load(any(), any(), any(), capture(callbacks)) } just Runs

        AdManager.resetForTesting()
        BillingManager.resetCacheForTesting()
        BillingManager.updateCachedEntitlement(false)
        assertFalse("Initially not entitled", BillingManager.isUserEntitled(context))

        AdManager.loadInterstitial(context, isPremium = false)
        assertEquals(1, callbacks.size)
        callbacks.single().onAdLoaded(dummyAd)
        assertTrue("Ad should be loaded for free user", AdManager.isAdLoaded())

        // Purchases restored -> update cached entitlement
        BillingManager.updateCachedEntitlement(true)
        assertTrue("Restored entitlement must reflect immediately from cache", BillingManager.isUserEntitled(context))

        AdManager.clearAd()
        assertFalse("Ad state must be purged upon entitlement update", AdManager.isAdLoaded())

        // Calling showInterstitialIfReady after purchase restoration must complete without showing
        var dismissedCalled = false
        val activity = mockk<android.app.Activity>(relaxed = true)
        every { activity.isFinishing } returns false
        every { activity.isDestroyed } returns false
        AdManager.showInterstitialIfReady(activity, isPremium = BillingManager.isUserEntitled(context)) {
            dismissedCalled = true
        }
        assertTrue("Dismiss callback must be invoked immediately for entitled user", dismissedCalled)
        assertFalse("No ad should remain loaded", AdManager.isAdLoaded())
    }

    private fun assertNoActivityReferences(target: Any, targetDescription: String) {
        val targetClass = target.javaClass
        assertFalse(
            "$targetDescription must not directly reference an Activity",
            android.app.Activity::class.java.isAssignableFrom(targetClass)
        )

        val className = targetClass.name
        if (!className.startsWith("java.") && !className.startsWith("android.")) {
            for (field in targetClass.declaredFields) {
                var innerVal: Any? = null
                var accessible = false
                try {
                    field.isAccessible = true
                    innerVal = field.get(target)
                    accessible = true
                } catch (e: Exception) {
                    if (e is ReflectiveOperationException || e is SecurityException || e.javaClass.simpleName == "InaccessibleObjectException") {
                        // Inaccessible under JVM security manager / module, skip
                    } else {
                        throw e
                    }
                }

                if (accessible && innerVal != null) {
                    assertFalse(
                        "$targetDescription field '${field.name}' must not reference an Activity",
                        android.app.Activity::class.java.isAssignableFrom(innerVal.javaClass)
                    )
                }
            }
        }
    }
}
