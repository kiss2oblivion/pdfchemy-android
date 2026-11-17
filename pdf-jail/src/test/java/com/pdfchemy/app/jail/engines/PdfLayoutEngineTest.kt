package com.pdfchemy.app.jail.engines

import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.NUpMode
import com.pdfchemy.app.logic.TargetPaperSize
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class PdfLayoutEngineTest {

    @Before
    fun initPdfBox() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun createFixtureBytes(rotation: Int, annotated: Boolean = false, withForm: Boolean = false): ByteArray {
        val baos = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(400f, 600f))
            page.cropBox = PDRectangle(40f, 60f, 200f, 300f)
            page.rotation = rotation
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.setNonStrokingColor(1f, 0f, 0f)
                content.addRect(55f, 80f, 35f, 45f)
                content.fill()
                content.setNonStrokingColor(0f, 0f, 1f)
                content.addRect(175f, 280f, 45f, 55f)
                content.fill()
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(85f, 200f)
                content.showText("VECTOR")
                content.endText()
            }
            if (annotated) {
                page.annotations.add(
                    PDAnnotationText().apply {
                        rectangle = PDRectangle(100f, 100f, 25f, 25f)
                        contents = "Keep this annotation"
                    }
                )
            }
            if (withForm) {
                val acroForm = PDAcroForm(document)
                document.documentCatalog.acroForm = acroForm
                val field = PDTextField(acroForm).apply { partialName = "field" }
                acroForm.fields.add(field)
            }
            document.save(baos)
        }
        return baos.toByteArray()
    }

    @Test
    fun resizeAndNupPreserveVectorTextAcrossRotations() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val srcBytes = createFixtureBytes(rotation)
            val resizeBytes = ByteArrayOutputStream()
            val nupBytes = ByteArrayOutputStream()

            PDDocument.load(srcBytes).use { srcDoc ->
                PdfLayoutEngine.requireStaticPages(srcDoc)
                PDDocument().use { outDoc ->
                    val layerUtil = LayerUtility(outDoc)
                    val imported = PdfLayoutEngine.importVisiblePage(layerUtil, srcDoc, 0)
                    val sideways = rotation % 180 == 90
                    assertEquals(if (sideways) 300f else 200f, imported.width, 0.01f)
                    assertEquals(if (sideways) 200f else 300f, imported.height, 0.01f)
                    val targetBaseW = TargetPaperSize.A4.widthPts
                    val targetBaseH = TargetPaperSize.A4.heightPts
                    val isLandscape = imported.width > imported.height
                    val targetW = if (isLandscape) maxOf(targetBaseW, targetBaseH) else minOf(targetBaseW, targetBaseH)
                    val targetH = if (isLandscape) minOf(targetBaseW, targetBaseH) else maxOf(targetBaseW, targetBaseH)
                    val newPage = PDPage(PDRectangle(targetW, targetH))
                    outDoc.addPage(newPage)
                    val scale = minOf(targetW / imported.width, targetH / imported.height)
                    val tx = (targetW - imported.width * scale) / 2f
                    val ty = (targetH - imported.height * scale) / 2f
                    PDPageContentStream(outDoc, newPage).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(Matrix(scale, 0f, 0f, scale, tx, ty))
                        cs.drawForm(imported.form)
                        cs.restoreGraphicsState()
                    }
                    outDoc.save(resizeBytes)
                }

                PDDocument().use { outDoc ->
                    val layerUtil = LayerUtility(outDoc)
                    val imported = PdfLayoutEngine.importVisiblePage(layerUtil, srcDoc, 0)
                    val sheetPage = PDPage(PDRectangle(TargetPaperSize.A4.widthPts, TargetPaperSize.A4.heightPts))
                    outDoc.addPage(sheetPage)
                    val cellW = (TargetPaperSize.A4.widthPts - 48f) / NUpMode.TWO_UP.cols
                    val cellH = (TargetPaperSize.A4.heightPts - 48f) / NUpMode.TWO_UP.rows
                    val scale = minOf((cellW - 12f) / imported.width, (cellH - 12f) / imported.height)
                    val drawX = 24f + 6f + ((cellW - 12f - imported.width * scale) / 2f)
                    val drawY = TargetPaperSize.A4.heightPts - 24f - cellH + 6f + ((cellH - 12f - imported.height * scale) / 2f)
                    PDPageContentStream(outDoc, sheetPage).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(Matrix(scale, 0f, 0f, scale, drawX, drawY))
                        cs.drawForm(imported.form)
                        cs.restoreGraphicsState()
                    }
                    outDoc.save(nupBytes)
                }
            }

            val resizeText = PDDocument.load(resizeBytes.toByteArray()).use { PDFTextStripper().getText(it) }
            val nupText = PDDocument.load(nupBytes.toByteArray()).use { PDFTextStripper().getText(it) }
            assertTrue("Text missing after resize rot=$rotation: [$resizeText]", resizeText.filterNot(Char::isWhitespace).contains("VECTOR"))
            assertTrue("Text missing after nup rot=$rotation: [$nupText]", nupText.filterNot(Char::isWhitespace).contains("VECTOR"))
        }
    }

    @Test
    fun requireStaticPagesRejectsAnnotatedAndFormPdfs() {
        for ((annotated, withForm) in listOf(true to false, false to true)) {
            PDDocument.load(createFixtureBytes(0, annotated = annotated, withForm = withForm)).use { doc ->
                try {
                    PdfLayoutEngine.requireStaticPages(doc)
                    fail("Expected requireStaticPages to reject non-static document")
                } catch (_: IllegalArgumentException) {
                    // Expected
                }
            }
        }
    }
}
