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
    private fun verifyContexts(share: (Context) -> Unit) {
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
                share(context)
                val chooser = shadowOf(application).nextStartedActivity
                assertNotNull("Sharing must launch a chooser", chooser)
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                assertEquals(
                    "Task behavior must match the underlying context",
                    requiresNewTask,
                    chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
                )
                @Suppress("DEPRECATION")
                val payload = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
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
}
