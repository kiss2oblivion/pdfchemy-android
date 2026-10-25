package com.pdfchemy.app.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.*
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual isolated annotation-only exports, independent of the redaction raster path. */
@RunWith(AndroidJUnit4::class)
class EditorLargePageExportSecurityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val publicText = "PUBLIC_LARGE_PAGE_CONTENT"

    @After fun cleanup() { DocumentStager.releaseAll() }

    @Test fun a3PortraitAnnotationExportIsBoundedAndAligned() = verify(PDRectangle.A3)
    @Test fun a3LandscapeAnnotationExportIsBoundedAndAligned() = verify(landscape(PDRectangle.A3))
    @Test fun a2PortraitAnnotationExportIsBoundedAndAligned() = verify(PDRectangle.A2)
    @Test fun a2LandscapeAnnotationExportIsBoundedAndAligned() = verify(landscape(PDRectangle.A2))
    @Test fun a4AnnotationExportStillPreservesVectorContent() = verify(PDRectangle.A4)
    @Test fun letterAnnotationExportStillPreservesVectorContent() = verify(PDRectangle.LETTER)

    @Test fun a3CropOffsetsAnd90RotationRemainAligned() = verify(
        PDRectangle(-40f, 30f, PDRectangle.A2.width, PDRectangle.A2.height),
        PDRectangle(50f, 100f, PDRectangle.A3.width, PDRectangle.A3.height), rotation = 90)

    @Test fun a2CropOffsetsAnd270RotationWithRequestedTurnRemainAligned() = verify(
        PDRectangle(-30f, 40f, PDRectangle.A1.width, PDRectangle.A1.height),
        PDRectangle(60f, 130f, PDRectangle.A2.width, PDRectangle.A2.height), rotation = 270, addedRotation = 90)

    private fun landscape(box: PDRectangle) = PDRectangle(box.height, box.width)

    private fun assertBox(label: String, expected: PDRectangle, actual: PDRectangle) {
        assertEquals("$label left", expected.lowerLeftX, actual.lowerLeftX, .001f)
        assertEquals("$label bottom", expected.lowerLeftY, actual.lowerLeftY, .001f)
        assertEquals("$label right", expected.upperRightX, actual.upperRightX, .001f)
        assertEquals("$label top", expected.upperRightY, actual.upperRightY, .001f)
    }

    private fun centroid(bitmap: Bitmap, blue: Boolean): Pair<Double, Double> {
        var x = 0L; var y = 0L; var n = 0L
        for (py in 0 until bitmap.height) for (px in 0 until bitmap.width) {
            val color = bitmap.getPixel(px, py)
            if (if (blue) Color.blue(color) > 120 && Color.red(color) < 60 && Color.green(color) < 60
                else Color.green(color) > 120 && Color.red(color) < 60 && Color.blue(color) < 60) {
                x += px; y += py; n++
            }
        }
        assertTrue("Expected visible registration/annotation mark", n > 20)
        return x.toDouble() / n / bitmap.width to y.toDouble() / n / bitmap.height
    }

    private fun verify(media: PDRectangle, crop: PDRectangle? = null,
                       rotation: Int = 0, addedRotation: Int = 0) = runBlocking<Unit> {
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("editor_large_page_", ".pdf", context.cacheDir)
        val output = File.createTempFile("editor_large_export_", ".pdf", context.cacheDir)
        try {
            PDDocument().use { doc ->
                val page = PDPage(media).also(doc::addPage)
                if (crop != null) page.cropBox = crop
                page.rotation = rotation
                val visible = crop ?: media
                PDPageContentStream(doc, page).use { cs ->
                    cs.setNonStrokingColor(0, 0, 255)
                    cs.addRect(visible.lowerLeftX + visible.width * .1f,
                        visible.lowerLeftY + visible.height * .8f,
                        visible.width * .08f, visible.height * .08f)
                    cs.fill()
                    cs.setNonStrokingColor(0, 0, 0)
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(visible.lowerLeftX + visible.width * .2f,
                        visible.lowerLeftY + visible.height * .5f)
                    cs.showText(publicText); cs.endText()
                }
                doc.save(source)
            }
            val original = source.readBytes()
            val originalText = PDDocument.load(source).use { input ->
                // Rotated text extraction may insert line breaks between glyphs.
                PDFTextStripper().getText(input).filterNot { it.isWhitespace() }
            }
            assertTrue("Input positive text control: $originalText", originalText.contains(publicText))
            val mod = PageModification(0, rotationDegrees = addedRotation,
                drawings = listOf(DrawingPath(points = listOf(DrawingPoint(.2f, .75f), DrawingPoint(.4f, .75f)),
                    color = Color.GREEN, strokeWidth = 12f)),
                textAnnotations = listOf(TextAnnotation(text = "Large page note", xRatio = .55f, yRatio = .35f)))
            assertTrue("Large-page annotations must fit existing raster quotas",
                PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to mod)).getOrThrow())
            assertArrayEquals("Export must not mutate the source", original, source.readBytes())
            PDDocument.load(output).use { saved ->
                assertEquals(1, saved.numberOfPages)
                val page = saved.getPage(0)
                assertBox("MediaBox", media, page.mediaBox)
                assertBox("CropBox", crop ?: media, page.cropBox)
                assertEquals((rotation + addedRotation) % 360, page.rotation)
                val savedText = PDFTextStripper().getText(saved).filterNot { it.isWhitespace() }
                assertTrue("Annotation-only export must retain original searchable content", savedText.contains(publicText))
                assertEquals("Every original text character must survive the annotation-only export", originalText, savedText)
                val images = page.resources.xObjectNames.map { page.resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
                assertEquals("The real export must contain the annotation overlay", 1, images.size)
                val image = images.single()
                assertTrue(image.width in 500..SecurityLimits.MAX_RENDER_DIMENSION)
                assertTrue(image.height in 500..SecurityLimits.MAX_RENDER_DIMENSION)
                assertTrue(image.width.toLong() * image.height <= SecurityLimits.MAX_RENDER_PIXELS)
            }

            val before = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(source), 0, 600))
            try {
                val after = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 0, 600))
                try {
                    fun turned(point: Pair<Double, Double>) = if (addedRotation == 90)
                        1.0 - point.second to point.first else point
                    val expectedAspect = before.width.toDouble() / before.height
                    assertEquals(if (addedRotation == 90) 1.0 / expectedAspect else expectedAspect,
                        after.width.toDouble() / after.height, .01)
                    val blueBefore = turned(centroid(before, true)); val blueAfter = centroid(after, true)
                    assertEquals("Public mark horizontal alignment", blueBefore.first, blueAfter.first, .015)
                    assertEquals("Public mark vertical alignment", blueBefore.second, blueAfter.second, .015)
                    val expectedGreen = turned(.3 to .75); val green = centroid(after, false)
                    assertEquals("Annotation horizontal alignment", expectedGreen.first, green.first, .025)
                    assertEquals("Annotation vertical alignment", expectedGreen.second, green.second, .025)
                } finally { after.recycle() }
            } finally { before.recycle() }
        } catch (failure: Throwable) {
            val evidence = File(context.getExternalFilesDir(null), "editor-large-page-failures").apply { mkdirs() }
            source.copyTo(File(evidence, source.name))
            output.copyTo(File(evidence, output.name))
            throw failure
        } finally { source.delete(); output.delete() }
    }
}
