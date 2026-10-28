package com.pdfchemy.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.DeskewPreviewLoader
import com.pdfchemy.app.logic.DeskewPreviewResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Actual composition, controlled out-of-order producer completions, real native bitmaps. */
class DeskewPreviewIntegrityTest {
    @get:Rule val compose = createComposeRule()
    private inner class Fixture {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val first = File.createTempFile("deskew_preview_a_", ".pdf", app.cacheDir)
        val second = File.createTempFile("deskew_preview_b_", ".pdf", app.cacheDir)
        val a = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val source = mutableStateOf(Uri.fromFile(first))
        val visible = mutableStateOf(true)
        val aStarted = AtomicBoolean(); val bStarted = AtomicBoolean()
        val aGate = CompletableDeferred<Unit>(); val bGate = CompletableDeferred<Unit>()
        val aReturned = AtomicBoolean(); val bReturned = AtomicBoolean()
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        fun preview(value: Float) = hasContentDescription("Page Preview") and SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, String.format(java.util.Locale.getDefault(), "%.1f°", value))
        fun show(failSecond: Boolean = false) {
            compose.runOnUiThread { model = MainViewModel(app); store.put("preview", model) }
            val loader = DeskewPreviewLoader { uri ->
                // Model uninterruptible native rendering. Bitmap ownership transfers only on return.
                withContext(NonCancellable) {
                    if (uri == Uri.fromFile(first)) {
                        aStarted.set(true); aGate.await(); aReturned.set(true)
                        DeskewPreviewResult(a, 5f)
                    } else {
                        bStarted.set(true); bGate.await(); bReturned.set(true)
                        if (failSecond) throw IOException("Controlled preview failure")
                        DeskewPreviewResult(b, -7f)
                    }
                }
            }
            compose.setContent {
                CompositionLocalProvider(LocalDeskewPreviewLoader provides loader) {
                    MaterialTheme { if (visible.value) DeskewScreen(model, source.value, onBack = {}) }
                }
            }
            compose.waitUntil(10_000) { aStarted.get() }
        }
        fun replace() {
            compose.runOnIdle { source.value = Uri.fromFile(second) }
            compose.waitUntil(10_000) { bStarted.get() }
        }
        fun close() {
            compose.runOnIdle { visible.value = false }
            aGate.complete(Unit); bGate.complete(Unit)
            compose.waitUntil(10_000) { (!aStarted.get() || aReturned.get()) && (!bStarted.get() || bReturned.get()) }
            compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            for (bitmap in listOf(a, b)) if (!bitmap.isRecycled) bitmap.recycle()
            first.delete(); second.delete()
        }
    }
    @Test fun replacementRejectsOutOfOrderBitmapAndAngle() {
        val f = Fixture()
        try {
            f.show(); f.replace(); f.bGate.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodes(f.preview(-7f)).fetchSemanticsNodes().size == 1 }
            f.aGate.complete(Unit)
            compose.waitUntil(10_000) { f.a.isRecycled || compose.onAllNodes(f.preview(5f)).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Stale result must be recycled", f.a.isRecycled)
            assertFalse("Latest bitmap must remain caller-owned", f.b.isRecycled)
            compose.onNode(f.preview(-7f)).assertExists()
            compose.onAllNodes(f.preview(5f)).assertCountEquals(0)
        } finally { f.close() }
    }
    @Test fun failedReplacementCannotShowPreviousDocumentPreview() {
        val f = Fixture()
        try {
            f.show(failSecond = true); f.aGate.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodes(f.preview(5f)).fetchSemanticsNodes().size == 1 }
            f.replace(); f.bGate.complete(Unit)
            compose.waitUntil(10_000) { f.bReturned.get() }
            compose.waitForIdle()
            assertTrue("Previous document bitmap must be retired at replacement", f.a.isRecycled)
            compose.onAllNodes(f.preview(5f)).assertCountEquals(0)
        } finally { f.close() }
    }
    @Test fun disposalBeforeProducerReturnRecyclesRejectedBitmap() {
        val f = Fixture()
        try {
            f.show()
            compose.runOnIdle { f.visible.value = false }
            f.aGate.complete(Unit)
            compose.waitUntil(10_000) { f.a.isRecycled }
            compose.onAllNodesWithContentDescription("Page Preview").assertCountEquals(0)
        } finally { f.close() }
    }
    @Test fun disposalAfterPublicationRecyclesCurrentBitmap() {
        val f = Fixture()
        try {
            f.show(); f.aGate.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodes(f.preview(5f)).fetchSemanticsNodes().size == 1 }
            assertFalse(f.a.isRecycled)
            compose.runOnIdle { f.visible.value = false }
            compose.waitUntil(10_000) { f.a.isRecycled }
        } finally { f.close() }
    }
}
