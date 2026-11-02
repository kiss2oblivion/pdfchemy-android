package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PdfRedactionEngine
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.RedactionConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchRedactionObjectSecurityTest {
    private fun exportLinkedDocument(action: Boolean, rotation: Int = 0, inspectPixels: Boolean = false, multiline: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc02_source_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc02_output_", ".pdf", context.cacheDir)
        val secret = "SC02_PRIVATE_731904"
        try {
            PDDocument().use { doc ->
                val privatePage = PDPage(PDRectangle(300f, 200f)).also(doc::addPage)
                if (inspectPixels) {
                    privatePage.cropBox = PDRectangle(10f, 20f, 260f, 160f)
                    privatePage.rotation = rotation
                }
                val publicPage = PDPage(PDRectangle(300f, 200f)).also(doc::addPage)
                PDPageContentStream(doc, privatePage).use {
                    it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                    it.newLineAtOffset(30f, 100f); it.showText(secret); it.endText()
                    if (multiline) {
                        it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                        it.newLineAtOffset(30f, 60f); it.showText(secret); it.endText()
                    }
                }
                PDPageContentStream(doc, publicPage).use {
                    it.beginText(); it.setFont(PDType1Font.COURIER, 12f)
                    it.newLineAtOffset(30f, 100f); it.showText("KEEP_PUBLIC_VECTOR"); it.endText()
                }
                publicPage.annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(10f, 10f, 100f, 30f)
                    val target = PDPageFitDestination().apply { page = privatePage }
                    if (action) this.action = PDActionGoTo().apply { destination = target }
                    else destination = target
                })
                doc.save(source)
            }
            val targets = PdfRedactionEngine.searchRedactionTargets(context, Uri.fromFile(source), secret).getOrThrow()
            assertEquals("Real worker search must find every private line", if (multiline) 2 else 1, targets.size)
            assertTrue(targets.all { it.pageIndex == 0 })
            // The shared host output contract currently reports output count (one),
            // not matched-box count. Verify every line through saved-data absence.
            assertTrue(PdfRedactionEngine.applyRedactions(context, Uri.fromFile(source), Uri.fromFile(output), targets,
                RedactionConfig(isBlackout = true, defaultOverlayText = "", forensicSanitize = true)).getOrThrow() > 0)
            PDDocument.load(output).use { saved ->
                assertEquals(2, saved.numberOfPages)
                val text = PDFTextStripper().getText(saved)
                assertFalse("Page-tree extraction must omit redacted text", text.contains(secret))
                assertTrue("Independent page must retain public vector content", text.contains("KEEP_PUBLIC_VECTOR"))
                for (obj in saved.document.objects) {
                    val stream = obj.`object` as? COSStream ?: continue
                    val decoded = stream.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    assertFalse("Original private stream must not survive through an imported link", decoded.contains(secret))
                }
                val link = saved.getPage(1).annotations.single() as PDAnnotationLink
                val destination = (if (action) (link.action as PDActionGoTo).destination else link.destination) as PDPageFitDestination
                assertSame("Internal navigation must target the sanitized page", saved.getPage(0).cosObject, destination.page.cosObject)
                if (inspectPixels) {
                    assertEquals(rotation, saved.getPage(0).rotation)
                    assertEquals(10f, saved.getPage(0).cropBox.lowerLeftX, 0f)
                    assertEquals(20f, saved.getPage(0).cropBox.lowerLeftY, 0f)
                }
            }
            if (inspectPixels) {
                val original = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(source), 0, 600))
                val redacted = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 0, 600))
                try {
                    assertEquals(original.width, redacted.width); assertEquals(original.height, redacted.height)
                    var left = original.width; var top = original.height; var right = 0; var bottom = 0
                    for (y in 0 until original.height) for (x in 0 until original.width) {
                        if (android.graphics.Color.red(original.getPixel(x, y)) < 100) {
                            left = minOf(left, x); right = maxOf(right, x)
                            top = minOf(top, y); bottom = maxOf(bottom, y)
                        }
                    }
                    assertTrue("Source must visibly contain the private glyphs", right > left && bottom > top)
                    for (y in top + 1 until bottom step 2) for (x in left + 1 until right step 3) {
                        val color = redacted.getPixel(x, y)
                        assertTrue("Opaque blackout must cover the entire original glyph region at rotation $rotation ($x,$y)",
                            android.graphics.Color.red(color) < 20 && android.graphics.Color.green(color) < 20 && android.graphics.Color.blue(color) < 20)
                    }
                } finally { original.recycle(); redacted.recycle() }
            }
        } finally { source.delete(); output.delete() }
    }

    @Test(timeout = 180000) fun directLinkCannotRetainOriginalSensitivePage() = exportLinkedDocument(false)
    @Test(timeout = 180000) fun goToActionCannotRetainOriginalSensitivePage() = exportLinkedDocument(true)
    @Test(timeout = 180000) fun cropAndRotation0CoverPrivateGlyphs() = exportLinkedDocument(false, 0, true)
    @Test(timeout = 180000) fun cropAndRotation90CoverPrivateGlyphs() = exportLinkedDocument(true, 90, true)
    @Test(timeout = 180000) fun cropAndRotation180CoverPrivateGlyphs() = exportLinkedDocument(false, 180, true)
    @Test(timeout = 180000) fun cropAndRotation270CoverPrivateGlyphs() = exportLinkedDocument(true, 270, true)
    @Test(timeout = 180000) fun multilineTargetsCannotRetainOriginalSensitivePage() = exportLinkedDocument(true, multiline = true)

    @Test(timeout = 180000) fun clippedSharedFormCannotRetainPrivateStreamThroughUnselectedPage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc02_shared_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc02_shared_out_", ".pdf", context.cacheDir)
        val secret = "SC02_CLIPPED_PRIVATE_371094"
        try {
            PDDocument().use { doc ->
                val first = PDPage(PDRectangle(300f, 100f)).also(doc::addPage)
                val second = PDPage(PDRectangle(300f, 100f)).also(doc::addPage)
                val third = PDPage(PDRectangle(300f, 100f)).also(doc::addPage)
                val shared = PDFormXObject(doc).apply {
                    resources = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
                    bBox = PDRectangle(300f, 100f)
                }
                shared.cosObject.createOutputStream().use {
                    it.write(("BT /F1 3 Tf 5 50 Td ($secret) Tj ET " +
                        "BT /F1 10 Tf 160 50 Td (KEEP_SHARED) Tj ET").toByteArray())
                }
                PDPageContentStream(doc, first).use { it.drawForm(shared) }
                PDPageContentStream(doc, second).use {
                    it.addRect(150f, 0f, 150f, 100f); it.clip(); it.drawForm(shared)
                }
                PDPageContentStream(doc, third).use {
                    it.beginText(); it.setFont(PDType1Font.COURIER, 10f)
                    it.newLineAtOffset(10f, 50f); it.showText("KEEP_INDEPENDENT_VECTOR"); it.endText()
                }
                third.annotations.add(PDAnnotationLink().apply {
                    rectangle = PDRectangle(10f, 10f, 100f, 20f)
                    destination = PDPageFitDestination().apply { page = first }
                })
                doc.save(source)
            }
            val selected = PdfRedactionEngine.searchRedactionTargets(context, Uri.fromFile(source), secret).getOrThrow()
                .filter { it.pageIndex == 0 }
            assertEquals(1, selected.size)
            assertTrue(PdfRedactionEngine.applyRedactions(context, Uri.fromFile(source), Uri.fromFile(output), selected,
                RedactionConfig(defaultOverlayText = "", forensicSanitize = true)).isSuccess)
            PDDocument.load(output).use { saved ->
                assertEquals(3, saved.numberOfPages)
                val text = PDFTextStripper().getText(saved)
                assertFalse(text.contains(secret))
                assertTrue(text.contains("KEEP_INDEPENDENT_VECTOR"))
                for (obj in saved.document.objects) {
                    val stream = obj.`object` as? COSStream ?: continue
                    assertFalse("Shared clipped original must be absent", stream.createInputStream().use {
                        String(it.readBytes(), Charsets.ISO_8859_1).contains(secret)
                    })
                }
                val link = saved.getPage(2).annotations.single() as PDAnnotationLink
                assertSame(saved.getPage(0).cosObject, (link.destination as PDPageFitDestination).page.cosObject)
            }
            val image = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 1, 600))
            try {
                var dark = 0
                for (y in 0 until image.height) for (x in image.width / 2 until image.width) {
                    if (android.graphics.Color.red(image.getPixel(x, y)) < 100) dark++
                }
                assertTrue("Visible public shared content must survive rasterization", dark > 50)
            } finally { image.recycle() }
        } finally { source.delete(); output.delete() }
    }
}
