package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as NativeColor
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.pdfchemy.app.logic.EditorTool
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.logic.RedactionBox
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual Compose images, with known native bitmap identities at the rendering boundary. */
class EditorOverlayBitmapLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private val modification = mutableStateOf(page("first"))
    private val source = image(NativeColor.WHITE)
    private val gate = CountDownLatch(1)
    private lateinit var surface: View

    private fun page(label: String) = PageModification(0,
        redactions = listOf(RedactionBox(0, RectF(.1f, .1f, .9f, .9f), label)))
    private fun image(color: Int) = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun show(renderer: EditorOverlayRenderer) {
        compose.setContent {
            CompositionLocalProvider(LocalEditorOverlayRenderer provides renderer) {
                surface = LocalView.current
                Box(Modifier.fillMaxSize()) {
                    if (visible.value) AnnotationPageCanvas(source, modification.value, EditorTool.REDACT,
                        Color.Black, 4f, {}, {}, {}, {})
                    else Text("Editor closed")
                }
            }
        }
    }
    private fun awaitColor(expected: Int) {
        compose.waitUntil(5_000) {
            // View.draw uses the real Compose BitmapPainter and works on every supported API.
            compose.runOnIdle {
                val frame = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                try {
                    surface.draw(Canvas(frame))
                    frame.getPixel(frame.width / 2, frame.height / 2) == expected
                } finally { frame.recycle() }
            }
        }
    }
    @After fun close() {
        gate.countDown()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        // Published test images can still have graphics references; leave their lifetime to those references.
    }

    @Test fun publishedOverlaysRemainDrawableAfterReplacementAndDisposal() {
        val first = image(NativeColor.RED)
        val second = image(NativeColor.BLUE)
        show(EditorOverlayRenderer { mod, _, _ ->
            if (mod.redactions.single().overlayLabel == "first") first else second
        })
        awaitColor(NativeColor.RED)
        compose.runOnIdle { modification.value = page("second") }
        awaitColor(NativeColor.BLUE)
        assertFalse("A previously published image may still be referenced by a graphics consumer", first.isRecycled)
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Editor closed").assertExists()
        assertFalse("Composition disposal is not proof that graphics consumers have released the image", second.isRecycled)
        val consumer = image(NativeColor.WHITE)
        try {
            Canvas(consumer).drawBitmap(first, 0f, 0f, null)
            assertEquals(NativeColor.RED, consumer.getPixel(20, 20))
            Canvas(consumer).drawBitmap(second, 0f, 0f, null)
            assertEquals(NativeColor.BLUE, consumer.getPixel(20, 20))
        } finally { consumer.recycle() }
    }

    @Test fun cancelledUnpublishedOverlayIsRecycled() {
        val unpublished = image(NativeColor.GREEN)
        val started = AtomicBoolean()
        show(EditorOverlayRenderer { _, _, _ ->
            started.set(true)
            check(gate.await(10, TimeUnit.SECONDS)) { "Controlled renderer was not released" }
            unpublished
        })
        compose.waitUntil(5_000) { started.get() }
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("Editor closed").assertExists()
        gate.countDown()
        compose.waitUntil(5_000) { unpublished.isRecycled }
    }
}
