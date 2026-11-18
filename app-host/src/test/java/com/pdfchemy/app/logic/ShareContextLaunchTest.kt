package com.pdfchemy.app.logic

import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
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
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun asyncOpaqueIdViewAndShareRetainProviderMime() = kotlinx.coroutines.runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val mainProviderCall = java.util.concurrent.atomic.AtomicBoolean()
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) mainProviderCall.set(true)
                return "audio/wav"
            }
            override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) =
                if (u.lastPathSegment == "mime-only") throw UnsupportedOperationException("MIME-only stream")
                else android.database.MatrixCursor(arrayOf("_display_name", "_size")).apply { addRow(arrayOf<Any>("recording.wav", 42L)) }
            override fun insert(u: Uri, v: android.content.ContentValues?): Uri? = null
            override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
            override fun update(u: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
        }
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "opaque" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("opaque", provider)
        val uri = Uri.parse("content://opaque/document/123")
        // Robolectric's main dispatcher is explicitly replaced so suspending action entry points can finish.
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            DocumentActions.viewAsync(app, uri)
            assertEquals("audio/wav", shadowOf(app).nextStartedActivity.type)
            com.pdfchemy.app.utils.ProviderMetadata.clear()
            ShareUtil.shareFilesAsync(app, listOf(uri))
            val chooser = shadowOf(app).nextStartedActivity
            @Suppress("DEPRECATION") val payload = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals("audio/wav", payload.type)
            DocumentActions.viewAsync(app, Uri.parse("content://opaque/document/mime-only"))
            assertEquals("MIME-only provider must retain its type", "audio/wav", shadowOf(app).nextStartedActivity.type)
            assertEquals("No implicit provider MIME IPC on Main", false, mainProviderCall.get())
        } finally {
            com.pdfchemy.app.utils.ProviderMetadata.clear()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

}
