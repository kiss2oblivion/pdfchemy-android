package com.pdfchemy.app.logic

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareContextLaunchTest {
    private fun verifyContexts(action: String = Intent.ACTION_CHOOSER, launch: (Context) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val contexts = listOf(
                application to true,
                ContextWrapper(application) to true,
                activity to false,
                ContextWrapper(ContextWrapper(activity)) to false
            )
            for ((context, requiresNewTask) in contexts) {
                launch(context)
                val started = shadowOf(application).nextStartedActivity
                assertNotNull("Action must launch an activity", started)
                assertEquals(action, started.action)
                assertEquals("Task behavior must match the underlying context", requiresNewTask,
                    started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                @Suppress("DEPRECATION")
                val payload = if (action == Intent.ACTION_CHOOSER) {
                    started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                } else started
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    payload.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun singleDocumentShareLaunchesFromApplicationAndWrappedActivityContexts() = verifyContexts {
        ShareUtil.shareFiles(it, listOf(Uri.parse("content://fixture/document.pdf")))
    }

    @Test fun multipleDocumentShareLaunchesFromApplicationAndWrappedActivityContexts() = verifyContexts {
        ShareUtil.shareFiles(it, listOf(Uri.parse("content://fixture/one.pdf"), Uri.parse("content://fixture/two.pdf")))
    }

    @Test fun singleImageShareLaunchesFromApplicationAndWrappedActivityContexts() = verifyContexts {
        ShareUtil.shareFile(it, Uri.parse("content://fixture/image.png"), "image/png")
    }

    @Test fun externalViewLaunchesFromApplicationAndWrappedActivityContexts() = verifyContexts(Intent.ACTION_VIEW) {
        DocumentActions.view(it, Uri.parse("content://fixture/audio.wav"))
    }
}
