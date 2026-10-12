package com.pdfchemy.app.ui

import android.view.ViewGroup
import com.pdfchemy.app.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit regression tests for START-01: API 30 content container startup ordering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 33])
class StartupContentContainerTest {

    @Test
    fun launchInstallsFrameworkContentContainer() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertNotNull("Framework content container must be installed on startup", content)
        assertTrue("Compose content must be installed into content container", content.childCount > 0)
        controller.destroy()
    }

    @Test
    fun repeatedRecreationRetainsContentContainer() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(3) {
            controller.recreate()
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertNotNull("Content container must exist after recreation", content)
            assertTrue("Compose content must exist after recreation", content.childCount > 0)
        }
        controller.destroy()
    }

    @Test
    fun backgroundAndResumeRetainsContentContainer() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(3) {
            controller.pause().stop()
            controller.restart().start().resume()
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertNotNull("Content container must exist after background/resume", content)
            assertTrue("Compose content must exist after background/resume", content.childCount > 0)
        }
        controller.destroy()
    }
}
