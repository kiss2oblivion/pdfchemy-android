package com.pdfchemy.app

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupWindowRegressionTest {
    private fun launch(): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Normal startup: do not use isScreenshotRun, which skips consent/ads initialization.
        return ActivityScenario.launch(Intent(context, MainActivity::class.java))
    }

    private fun assertContent(scenario: ActivityScenario<MainActivity>) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertNotNull("Startup must install the framework content container", content)
            assertTrue("Startup must install Compose content", content.childCount > 0)
        }
    }

    @Test fun repeatedNormalLaunchAndRecreationInstallsContent() {
        repeat(10) {
            launch().use { scenario ->
                assertContent(scenario)
                scenario.recreate()
                assertContent(scenario)
            }
        }
    }

    @Test fun backgroundAndResumeRetainsContent() {
        launch().use { scenario ->
            repeat(10) {
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertContent(scenario)
            }
        }
    }
}
