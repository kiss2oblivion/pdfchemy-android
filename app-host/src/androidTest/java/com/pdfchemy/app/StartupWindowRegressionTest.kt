package com.pdfchemy.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val launcher = requireNotNull(application.packageManager.getLaunchIntentForPackage(application.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        assertEquals(Intent.ACTION_MAIN, launcher.action)
        assertTrue("Resume must use the normal launcher entry point", launcher.hasCategory(Intent.CATEGORY_LAUNCHER))
        // ActivityScenario matches lifecycle events by intent filters. MainActivity stores
        // onNewIntent(), so the first launch and launcher return must use the same filters.
        ActivityScenario.launch<MainActivity>(Intent(launcher)).use { scenario ->
            assertContent(scenario)
            lateinit var originalActivity: MainActivity
            lateinit var originalContent: ViewGroup
            lateinit var originalCompose: View
            var originalTask = -1
            scenario.onActivity { activity ->
                originalActivity = activity
                originalContent = activity.findViewById(android.R.id.content)
                originalCompose = originalContent.getChildAt(0)
                originalTask = activity.taskId
                assertFalse("Normal startup must remain enabled", activity.intent.getBooleanExtra("isScreenshotRun", false))
            }
            val activeCycle = AtomicReference<BackgroundCycle?>()
            val destroyed = AtomicBoolean(false)
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                private fun record(activity: Activity, stage: String) {
                    if (activity === originalActivity) activeCycle.get()?.record(stage)
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = record(activity, "STARTED")
                override fun onActivityResumed(activity: Activity) = record(activity, "RESUMED")
                override fun onActivityPaused(activity: Activity) = record(activity, "PAUSED")
                override fun onActivityStopped(activity: Activity) = record(activity, "STOPPED")
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    if (activity === originalActivity) {
                        destroyed.set(true)
                        activeCycle.get()?.record("DESTROYED")
                    }
                }
            }
            instrumentation.runOnMainSync { application.registerActivityLifecycleCallbacks(callbacks) }
            try {
                repeat(10) { index ->
                    scenario.onActivity { activity ->
                        assertSame(originalActivity, activity)
                        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                    }
                    val cycle = BackgroundCycle(index + 1)
                    activeCycle.set(cycle)
                    assertTrue("Home action must succeed for cycle ${cycle.number}",
                        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                    val stopped = cycle.stopped.await(30, TimeUnit.SECONDS)
                    assertTrue("Home must stop the original activity: ${cycle.evidence()}", stopped)
                    assertFalse("Backgrounding must not destroy the original activity", destroyed.get())
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        assertEquals("Home must make the activity nonvisible", Lifecycle.State.CREATED, originalActivity.lifecycle.currentState)
                        assertFalse(originalActivity.isFinishing)
                        assertFalse(originalActivity.isDestroyed)
                    }
                    assertEquals("Home must deliver real pause and stop callbacks", listOf("PAUSED", "STOPPED"), cycle.stages())

                    cycle.resumeArmed.set(true)
                    application.startActivity(Intent(launcher))
                    val resumed = cycle.resumed.await(30, TimeUnit.SECONDS)
                    assertTrue("Launcher must resume the original activity: ${cycle.evidence()}", resumed)
                    assertFalse("Resume must retain the original activity", destroyed.get())
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertSame("Launcher must return to the same activity", originalActivity, activity)
                        assertEquals(originalTask, activity.taskId)
                        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                        assertFalse(activity.isFinishing)
                        assertFalse(activity.isDestroyed)
                        val content = activity.findViewById<ViewGroup>(android.R.id.content)
                        assertSame("Backgrounding must retain the installed content container", originalContent, content)
                        assertTrue("Resume must retain Compose content", content.childCount > 0)
                        assertSame("Resume must retain the installed Compose view", originalCompose, content.getChildAt(0))
                        assertEquals("Each cycle must background and resume in order",
                            listOf("PAUSED", "STOPPED", "STARTED", "RESUMED"), cycle.stages())
                        Log.i("StartupWindowRegression", "${cycle.evidence()} activity=${System.identityHashCode(activity)} " +
                            "task=${activity.taskId} content=${System.identityHashCode(content)} children=${content.childCount}")
                    }
                    activeCycle.set(null)
                }
            } finally {
                activeCycle.set(null)
                instrumentation.runOnMainSync { application.unregisterActivityLifecycleCallbacks(callbacks) }
            }
        }
    }

    private class BackgroundCycle(val number: Int) {
        val stopped = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        val resumeArmed = AtomicBoolean(false)
        private val observed = mutableListOf<String>()

        fun record(stage: String) {
            synchronized(observed) { observed.add(stage) }
            when (stage) {
                "STOPPED" -> stopped.countDown()
                "RESUMED" -> if (resumeArmed.get()) resumed.countDown()
                "DESTROYED" -> { stopped.countDown(); resumed.countDown() }
            }
        }

        fun stages(): List<String> = synchronized(observed) { observed.toList() }
        fun evidence(): String = "cycle=$number stages=${stages().joinToString("->")}"
    }
}
