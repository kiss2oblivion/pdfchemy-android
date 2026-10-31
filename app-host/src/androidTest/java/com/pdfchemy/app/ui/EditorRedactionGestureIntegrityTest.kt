package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.EditorTool
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.RedactionBox
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual canvas and native pointer events, including movement delivered only with ACTION_UP. */
class EditorRedactionGestureIntegrityTest {
    @get:Rule val compose = createComposeRule()
    private val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
    private val committed = mutableListOf<RedactionBox>()
    private val visible = mutableStateOf(true)
    @After fun cleanup() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        // Compose/RenderThread may retain this small fixture image after disposal.
        // Let its framework references expire; immediate recycling can break the next test's frame.
    }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    if (visible.value) AnnotationPageCanvas(bitmap, PageModification(0), EditorTool.REDACT,
                        Color.Black, 4f, {}, {}, {}, { committed.add(it) })
                }
            }
        }
    }

    private fun gesture(reverse: Boolean = false) {
        compose.onNodeWithContentDescription("Page 1").performTouchInput {
            fun point(ratio: Float) = Offset(width * ratio, height * ratio)
            down(point(if (reverse) .9f else .1f))
            moveTo(point(if (reverse) .8f else .2f))
            moveTo(point(if (reverse) .6f else .4f))
            // A real UP carries a position; it need not have a preceding MOVE there.
            updatePointerTo(0, point(if (reverse) .2f else .8f))
            up()
        }
    }

    @Test fun redactionIncludesReleasePosition() {
        show(); gesture()
        compose.runOnIdle {
            assertEquals(1, committed.size)
            assertEquals(.8f, committed.single().normalizedRect.right, .002f)
            assertEquals(.8f, committed.single().normalizedRect.bottom, .002f)
            assertEquals(.1f, committed.single().normalizedRect.left, .002f)
            assertEquals(.1f, committed.single().normalizedRect.top, .002f)
        }
    }

    @Test fun reverseRedactionIncludesReleasePosition() {
        show(); gesture(reverse = true)
        compose.runOnIdle {
            assertEquals(1, committed.size)
            assertEquals(.2f, committed.single().normalizedRect.left, .002f)
            assertEquals(.2f, committed.single().normalizedRect.top, .002f)
            assertEquals(.9f, committed.single().normalizedRect.right, .002f)
            assertEquals(.9f, committed.single().normalizedRect.bottom, .002f)
        }
    }

    @Test fun cancelledDragDoesNotCommit() {
        show()
        compose.onNodeWithContentDescription("Page 1").performTouchInput {
            down(Offset(width * .1f, height * .1f))
            moveTo(Offset(width * .4f, height * .4f))
            cancel()
        }
        compose.runOnIdle { assertTrue(committed.isEmpty()) }
    }

    @Test fun tapDoesNotCommit() {
        show()
        compose.onNodeWithContentDescription("Page 1").performTouchInput { click() }
        compose.runOnIdle { assertTrue(committed.isEmpty()) }
    }

    @Test fun exportedPdfCoversReleaseEndOfSelection() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)
        val source = File.createTempFile("gesture-source-", ".pdf", app.cacheDir)
        val output = File.createTempFile("gesture-output-", ".pdf", app.cacheDir)
        val secret = "TAIL_509"
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(200f, 200f)).also(document::addPage)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 8f)
                    stream.newLineAtOffset(120f, 60f); stream.showText(secret); stream.endText()
                }
                document.save(source)
            }
            val original = source.readBytes()
            show(); gesture()
            val box = compose.runOnIdle { committed.single() }
            assertTrue(PdfEditor.exportModifiedPdf(app, Uri.fromFile(source), Uri.fromFile(output),
                mapOf(0 to PageModification(0, redactions = listOf(box)))).getOrThrow())
            assertArrayEquals(original, source.readBytes())
            PDDocument.load(output).use { saved ->
                assertEquals(200f, saved.getPage(0).mediaBox.width, .001f)
                assertEquals(200f, saved.getPage(0).mediaBox.height, .001f)
                assertFalse(PDFTextStripper().getText(saved).contains(secret))
            }
            val rendered = requireNotNull(PdfEditor.renderPageBitmap(app, Uri.fromFile(output), 0, 200))
            try {
                // Below the label and original glyphs, but inside the requested final rectangle.
                for (y in 147..153) for (x in 147..153) {
                    assertEquals("The saved PDF must cover the release end, not stop at the last MOVE",
                        android.graphics.Color.BLACK, rendered.getPixel(x, y))
                }
            } finally { rendered.recycle() }
        } finally { source.delete(); output.delete() }
    }
}
