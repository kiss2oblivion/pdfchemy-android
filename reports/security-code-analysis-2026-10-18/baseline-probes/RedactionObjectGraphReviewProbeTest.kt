package com.pdfchemy.app.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RedactionObjectGraphReviewProbeTest {
    private fun retainEvidence(name: String, bytes: ByteArray) {
        System.getenv("PDFCHEMY_AUDIT_EVIDENCE")?.let { directory ->
            java.io.File(directory).apply { mkdirs() }.resolve(name).writeBytes(bytes)
        }
    }

    @Test fun editorOverwriteRetainsUnredactedOriginalFormResource_probe() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Application>())
        val secret = "AUDIT_ORIGINAL_RESOURCE_842613"
        PDDocument().use { original ->
            val page = PDPage()
            original.addPage(page)
            val form = PDFormXObject(original).apply {
                resources = PDResources()
                bBox = PDRectangle(200f, 100f)
            }
            form.resources.put(com.tom_roush.pdfbox.cos.COSName.getPDFName("F1"), PDType1Font.HELVETICA)
            form.cosObject.createOutputStream().use {
                it.write("BT /F1 12 Tf 20 50 Td ($secret) Tj ET".toByteArray(Charsets.US_ASCII))
            }
            PDPageContentStream(original, page).use { it.drawForm(form) }
            // Same replacement mode as PdfEditorWorker; raster itself need not contain original resources.
            PDPageContentStream(original, page, PDPageContentStream.AppendMode.OVERWRITE, false, false).use {
                it.addRect(0f, 0f, 200f, 100f)
                it.fill()
            }
            val bytes = ByteArrayOutputStream().also { original.save(it) }.toByteArray()
            retainEvidence("editor-retained-original-resource.pdf", bytes)
            PDDocument.load(bytes).use { saved ->
                assertFalse(PDFTextStripper().getText(saved).contains(secret))
                val leaked = saved.document.objects.mapNotNull { it.`object` as? COSStream }.any { stream ->
                    stream.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }.contains(secret)
                }
                assertTrue("Observed leak: OVERWRITE retains the original reachable form XObject", leaked)
            }
        }
    }

    @Test fun importedUnchangedPageLinkRetainsOriginalSensitivePageStream_probe() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Application>())
        val secret = "AUDIT_SECRET_731904"
        PDDocument().use { original ->
            val sensitive = PDPage()
            val unchanged = PDPage()
            original.addPage(sensitive)
            original.addPage(unchanged)
            PDPageContentStream(original, sensitive).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(30f, 700f)
                stream.showText(secret)
                stream.endText()
            }
            unchanged.annotations.add(PDAnnotationLink().apply {
                destination = PDPageFitDestination().apply { page = sensitive }
            })
            PDDocument().use { output ->
                // Stand-in for the newly rasterized target page: no original text/resource reference.
                output.addPage(PDPage())
                // Exact PDFBox operation used for unaffected pages by secure search-redaction export.
                output.importPage(unchanged)
                val bytes = ByteArrayOutputStream().also { output.save(it) }.toByteArray()
                retainEvidence("search-link-retained-original-page.pdf", bytes)
                PDDocument.load(bytes).use { saved ->
                    assertEquals(2, saved.numberOfPages)
                    assertFalse("Visible page-tree text must omit secret", PDFTextStripper().getText(saved).contains(secret))
                    val streamsWithSecret = saved.document.objects.mapNotNull { it.`object` as? COSStream }
                        .count { stream -> stream.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }.contains(secret) }
                    assertTrue("Observed leak: serialized non-page-tree original stream retains secret", streamsWithSecret > 0)
                }
            }
        }
    }
}
