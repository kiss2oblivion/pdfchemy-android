package com.pdfchemy.app.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import com.pdfchemy.app.ui.WindowSecureManager
import com.pdfchemy.app.ui.findActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Regression tests for SA-02: Window-level FLAG_SECURE coordination and lifecycle ownership.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WindowSecureManagerTest {

    private lateinit var activityController: ActivityController<Activity>
    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activityController = Robolectric.buildActivity(Activity::class.java).setup()
        activity = activityController.get()
        WindowSecureManager.resetForTesting()
    }

    @After
    fun tearDown() {
        WindowSecureManager.resetForTesting()
        activityController.destroy()
    }

    @Test
    fun singleScreenLifecycle_setsAndClearsFlag() {
        val window = activity.window
        assertFalse("Window should initially be unshielded", WindowSecureManager.isSecure(window))
        assertEquals(0, WindowSecureManager.activeLeaseCount(window))

        val lease = WindowSecureManager.acquire(window, tag = "ReaderScreen")
        assertTrue("Window must have FLAG_SECURE after lease acquisition", WindowSecureManager.isSecure(window))
        assertEquals(1, WindowSecureManager.activeLeaseCount(window))
        assertFalse(lease.isReleased)

        lease.release()
        assertFalse("Window must clear FLAG_SECURE after last lease release", WindowSecureManager.isSecure(window))
        assertEquals(0, WindowSecureManager.activeLeaseCount(window))
        assertTrue(lease.isReleased)
    }

    @Test
    fun overlappingReaderToEditorTransition_neverClearsFlagDuringHandoff() {
        val window = activity.window

        // 1. Reader screen is active
        val readerLease = WindowSecureManager.acquire(window, tag = "ReaderScreen")
        assertTrue(WindowSecureManager.isSecure(window))
        assertEquals(1, WindowSecureManager.activeLeaseCount(window))

        // 2. Editor screen enters composition before Reader is disposed (overlapping AnimatedContent)
        val editorLease = WindowSecureManager.acquire(window, tag = "EditorScreen")
        assertTrue("Window must remain secure with both leases", WindowSecureManager.isSecure(window))
        assertEquals(2, WindowSecureManager.activeLeaseCount(window))

        // 3. Reader screen is removed from composition and disposes its lease
        readerLease.release()
        assertTrue("CRITICAL SA-02 REGRESSION: Outgoing Reader must NOT clear FLAG_SECURE while Editor is active!",
            WindowSecureManager.isSecure(window))
        assertEquals(1, WindowSecureManager.activeLeaseCount(window))

        // 4. Editor screen eventually leaves to an unshielded screen
        editorLease.release()
        assertFalse("FLAG_SECURE must be cleared once all sensitive screens have exited",
            WindowSecureManager.isSecure(window))
        assertEquals(0, WindowSecureManager.activeLeaseCount(window))
    }

    @Test
    fun chainedTransitionsAcrossMultipleSensitiveScreens_retainsProtectionThroughout() {
        val window = activity.window

        val lease1 = WindowSecureManager.acquire(window, "Reader")
        assertTrue(WindowSecureManager.isSecure(window))

        // Reader -> Editor
        val lease2 = WindowSecureManager.acquire(window, "Editor")
        lease1.release()
        assertTrue("Still secure after Reader disposal", WindowSecureManager.isSecure(window))

        // Editor -> SignPdf
        val lease3 = WindowSecureManager.acquire(window, "SignPdf")
        lease2.release()
        assertTrue("Still secure after Editor disposal", WindowSecureManager.isSecure(window))

        // SignPdf -> Redaction
        val lease4 = WindowSecureManager.acquire(window, "Redaction")
        lease3.release()
        assertTrue("Still secure after SignPdf disposal", WindowSecureManager.isSecure(window))

        // Final exit
        lease4.release()
        assertFalse("Flag cleared after Redaction exit", WindowSecureManager.isSecure(window))
    }

    @Test
    fun rapidNavigationAndOutOfOrderRelease_preservesFlagUntilLastRelease() {
        val window = activity.window

        val l1 = WindowSecureManager.acquire(window, "Screen1")
        val l2 = WindowSecureManager.acquire(window, "Screen2")
        val l3 = WindowSecureManager.acquire(window, "Screen3")
        val l4 = WindowSecureManager.acquire(window, "Screen4")
        assertEquals(4, WindowSecureManager.activeLeaseCount(window))

        // Release out-of-order
        l2.release()
        assertTrue(WindowSecureManager.isSecure(window))
        assertEquals(3, WindowSecureManager.activeLeaseCount(window))

        l4.release()
        assertTrue(WindowSecureManager.isSecure(window))
        assertEquals(2, WindowSecureManager.activeLeaseCount(window))

        l1.release()
        assertTrue(WindowSecureManager.isSecure(window))
        assertEquals(1, WindowSecureManager.activeLeaseCount(window))

        // Release final remaining lease
        l3.release()
        assertFalse(WindowSecureManager.isSecure(window))
        assertEquals(0, WindowSecureManager.activeLeaseCount(window))
    }

    @Test
    fun idempotentRelease_doesNotUnderflowOrCorruptCount() {
        val window = activity.window

        val lease = WindowSecureManager.acquire(window, "Editor")
        assertEquals(1, WindowSecureManager.activeLeaseCount(window))

        lease.release()
        assertEquals(0, WindowSecureManager.activeLeaseCount(window))
        assertFalse(WindowSecureManager.isSecure(window))

        // Repeat releases on the same token
        lease.release()
        lease.release()
        assertEquals("Count must remain 0 after redundant release calls", 0, WindowSecureManager.activeLeaseCount(window))
        assertFalse(WindowSecureManager.isSecure(window))
    }

    @Test
    fun preservationOfIndependentlyEstablishedFlag_neverRemovesPreExistingSecurity() {
        val window = activity.window
        // Pre-establish FLAG_SECURE prior to WindowSecureManager (e.g. Activity policy or MDM)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        assertTrue(WindowSecureManager.isSecure(window))

        // Sensitive screen acquires lease
        val lease = WindowSecureManager.acquire(window, "ReaderScreen")
        assertTrue(WindowSecureManager.wasInitiallySecure(window))
        assertEquals(1, WindowSecureManager.activeLeaseCount(window))

        // Sensitive screen releases its lease
        lease.release()
        assertEquals(0, WindowSecureManager.activeLeaseCount(window))
        assertTrue("CRITICAL SA-02: Pre-existing external FLAG_SECURE must NOT be cleared by manager!",
            WindowSecureManager.isSecure(window))
    }

    @Test
    fun activityRecreation_isolatesWindowsCleanly() {
        val window1 = activity.window
        val lease1 = WindowSecureManager.acquire(window1, "Reader")
        assertTrue(WindowSecureManager.isSecure(window1))

        // Recreate activity (e.g. configuration change)
        val controller2 = Robolectric.buildActivity(Activity::class.java).setup()
        val activity2 = controller2.get()
        val window2 = activity2.window

        // Window2 is independent of Window1
        assertFalse(WindowSecureManager.isSecure(window2))

        val lease2 = WindowSecureManager.acquire(window2, "Reader-New")
        assertTrue(WindowSecureManager.isSecure(window2))

        // Release old window lease
        lease1.release()
        assertFalse(WindowSecureManager.isSecure(window1))
        assertTrue("Window2 must remain secure regardless of Window1 disposal", WindowSecureManager.isSecure(window2))

        lease2.release()
        assertFalse(WindowSecureManager.isSecure(window2))
        controller2.destroy()
    }

    @Test
    fun multiWindowIsolation_concurrentWindowsDoNotCrossContaminate() {
        val windowA = activity.window
        val controllerB = Robolectric.buildActivity(Activity::class.java).setup()
        val windowB = controllerB.get().window

        val leaseA = WindowSecureManager.acquire(windowA, "WindowA")
        assertTrue(WindowSecureManager.isSecure(windowA))
        assertFalse(WindowSecureManager.isSecure(windowB))

        val leaseB = WindowSecureManager.acquire(windowB, "WindowB")
        assertTrue(WindowSecureManager.isSecure(windowA))
        assertTrue(WindowSecureManager.isSecure(windowB))

        leaseA.release()
        assertFalse(WindowSecureManager.isSecure(windowA))
        assertTrue("WindowB must stay secure after WindowA releases", WindowSecureManager.isSecure(windowB))

        leaseB.release()
        assertFalse(WindowSecureManager.isSecure(windowB))
        controllerB.destroy()
    }

    @Test
    fun contextHierarchyActivityResolution_unwrapsDeepContextWrappers() {
        val directActivity: Context = activity
        assertEquals(activity, directActivity.findActivity())

        val wrapper1 = ContextWrapper(activity)
        assertEquals(activity, wrapper1.findActivity())

        val wrapper2 = ContextWrapper(wrapper1)
        val wrapper3 = ContextWrapper(wrapper2)
        assertEquals(activity, wrapper3.findActivity())

        val mockAppContext = Robolectric.buildActivity(Activity::class.java).get().applicationContext
        // Application context does not inherit Activity
        assertNull(ContextWrapper(mockAppContext).findActivity())
    }

    @Test
    fun backNavigation_restoresUnshieldedStateOnlyWhenAllSensitiveScreensExit() {
        val window = activity.window

        // Home -> Reader
        val readerLease = WindowSecureManager.acquire(window, "Reader")
        assertTrue(WindowSecureManager.isSecure(window))

        // Reader -> Editor
        val editorLease = WindowSecureManager.acquire(window, "Editor")
        assertTrue(WindowSecureManager.isSecure(window))

        // Back pressed: Editor removed, Reader still displayed
        editorLease.release()
        assertTrue("Returning back to Reader retains window security", WindowSecureManager.isSecure(window))

        // Back pressed again: Reader removed, returned to Home
        readerLease.release()
        assertFalse("Returning to Home clears window security", WindowSecureManager.isSecure(window))
    }
}
