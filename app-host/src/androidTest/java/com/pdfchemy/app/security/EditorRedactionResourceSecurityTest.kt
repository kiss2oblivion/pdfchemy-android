package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.RedactionBox
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorRedactionResourceSecurityTest {
    private fun runFixture(scan: Boolean, inherited: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc01_input_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc01_output_", ".pdf", context.cacheDir)
        val secret = "SC01_ORIGINAL_PRIVATE_RESOURCE_731904"
        val pixels = ByteArray(4 * 4 * 3) { ((it * 37 + 19) % 256).toByte() }
        try {
            PDDocument().use { doc ->
                val target = PDPage(PDRectangle(200f, 200f)).also(doc::addPage)
                val resources = PDResources()
                if (inherited) doc.pages.cosObject.setItem(COSName.RESOURCES, resources)
                else target.resources = resources
                if (scan) {
                    val image = PDImageXObject(doc, ByteArrayInputStream(pixels), null, 4, 4, 8, PDDeviceRGB.INSTANCE)
                    image.cosObject.setString(COSName.getPDFName("AuditOriginal"), secret)
                    PDPageContentStream(doc, target).use { it.drawImage(image, 0f, 0f, 200f, 200f) }
                } else {
                    val form = PDFormXObject(doc).apply {
                        this.resources = PDResources()
                        bBox = PDRectangle(200f, 200f)
                    }
                    form.resources.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
                    form.cosObject.createOutputStream().use {
                        it.write("BT /F1 10 Tf 10 100 Td ($secret) Tj ET".toByteArray(Charsets.US_ASCII))
                    }
                    PDPageContentStream(doc, target).use { it.drawForm(form) }
                }
                if (inherited) {
                    val other = PDPage(PDRectangle(200f, 200f)).also(doc::addPage)
                    PDPageContentStream(doc, other).use {
                        it.beginText(); it.setFont(PDType1Font.HELVETICA, 10f)
                        it.newLineAtOffset(10f, 100f); it.showText("KEEP_VECTOR_PAGE"); it.endText()
                    }
                }
                doc.save(source)
            }
            val mod = PageModification(0, redactions = listOf(RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")))
            assertTrue(PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to mod)).getOrThrow())
            PDDocument.load(output).use { saved ->
                val text = PDFTextStripper().getText(saved)
                assertFalse(text.contains(secret))
                if (inherited) assertTrue(text.contains("KEEP_VECTOR_PAGE"))
                assertFalse(String(output.readBytes(), Charsets.ISO_8859_1).contains(secret))
                for (obj in saved.document.objects) {
                    val stream = obj.`object` as? COSStream ?: continue
                    val bytes = stream.createInputStream().use { it.readBytes() }
                    assertFalse("Original decoded Form must be absent", String(bytes, Charsets.ISO_8859_1).contains(secret))
                    if (scan) assertFalse("Original scan pixels must be absent", bytes.contentEquals(pixels))
                }
            }
            val bitmap = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 0, 200))
            try {
                val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                assertTrue("Redaction must remain opaque black", android.graphics.Color.alpha(center) == 255 &&
                    android.graphics.Color.red(center) <= 3 && android.graphics.Color.green(center) <= 3 && android.graphics.Color.blue(center) <= 3)
            } finally { bitmap.recycle() }
        } finally { source.delete(); output.delete() }
    }

    @Test(timeout = 180000) fun formCannotSurviveRealEditorRedactionExport() = runFixture(false, false)
    @Test(timeout = 180000) fun originalScanCannotSurviveRealEditorRedactionExport() = runFixture(true, false)
    @Test(timeout = 180000) fun inheritedUnusedFormIsRemovedAndOtherVectorPageSurvives() = runFixture(false, true)
}
