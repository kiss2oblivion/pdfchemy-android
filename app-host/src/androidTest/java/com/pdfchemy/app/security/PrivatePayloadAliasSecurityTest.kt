package com.pdfchemy.app.security

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.PdfRedactionEngine
import com.pdfchemy.app.logic.RedactionBox
import com.pdfchemy.app.logic.RedactionConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/** Exercises the real isolated workers and native renderer, including saved private-data aliases. */
@RunWith(AndroidJUnit4::class)
class PrivatePayloadAliasSecurityTest {
    private val privatePayload = "REANALYSIS_NESTED_PRIVATE_PAYLOAD_529814"
    private val privateText = "SC10_PRIVATE_PAGE_529814"
    private val publicText = "KEEP_PUBLIC_VECTOR"
    private val alternatePayload = COSName.getPDFName("AlternatePayload")

    private fun runFixture(attachment: Boolean, search: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc10_alias_source_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc10_alias_output_", ".pdf", context.cacheDir)
        try {
            writeFixture(source, attachment)
            assertInputContainsAlias(source, attachment)
            if (search) {
                val targets = PdfRedactionEngine.searchRedactionTargets(context, Uri.fromFile(source), privateText).getOrThrow()
                assertEquals("Real worker search must find the private line", 1, targets.size)
                assertEquals(0, targets.single().pageIndex)
                assertTrue("Secure Search export must succeed", PdfRedactionEngine.applyRedactions(
                    context, Uri.fromFile(source), Uri.fromFile(output), targets,
                    RedactionConfig(isBlackout = true, defaultOverlayText = "", forensicSanitize = true)
                ).getOrThrow() > 0)
            } else {
                val modification = PageModification(0, redactions = listOf(
                    RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")
                ))
                assertTrue("Editor export must succeed", PdfEditor.exportModifiedPdf(
                    context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to modification)
                ).getOrThrow())
            }
            assertSavedDocument(output, attachment)
            assertNativePixels(context, source, output, search)
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun rejectUsedResourceAlias(search: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc10_shared_resource_source_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc10_shared_resource_output_", ".pdf", context.cacheDir)
        val existing = "SC10_EXISTING_DESTINATION_MUST_SURVIVE_741925".toByteArray(Charsets.UTF_8)
        try {
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle(300f, 200f)).also(doc::addPage)
                PDPageContentStream(doc, page).use {
                    it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                    it.newLineAtOffset(30f, 100f); it.showText(privateText); it.endText()
                }
                val font = page.resources.getFont(page.resources.fontNames.single()).cosObject
                doc.documentCatalog.cosObject.setItem(COSName.getPDFName("PieceInfo"), COSDictionary().apply {
                    setItem(COSName.getPDFName("PrivateApp"), COSDictionary().apply {
                        setString(COSName.getPDFName("LastModified"), "D:20261021000000Z")
                        setItem(COSName.getPDFName("Private"), font)
                    })
                })
                doc.save(source)
            }
            PDDocument.load(source).use { doc ->
                val pieceInfo = doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("PieceInfo")) as COSDictionary
                val application = pieceInfo.getDictionaryObject(COSName.getPDFName("PrivateApp")) as COSDictionary
                val page = doc.getPage(0)
                val usedFont = page.resources.getFont(page.resources.fontNames.single()).cosObject
                assertSame("Private descendant must alias a genuinely used font", usedFont,
                    application.getDictionaryObject(COSName.getPDFName("Private")))
            }
            val targets = PdfRedactionEngine.searchRedactionTargets(context, Uri.fromFile(source), privateText).getOrThrow()
            assertEquals("Valid shared-font input must be readable by the real worker", 1, targets.size)
            assertEquals(0, targets.single().pageIndex)
            output.writeBytes(existing)
            if (search) {
                val result = PdfRedactionEngine.applyRedactions(context, Uri.fromFile(source), Uri.fromFile(output), targets,
                    RedactionConfig(isBlackout = true, defaultOverlayText = "", forensicSanitize = true))
                assertTrue("Secure Search must reject private data shared with a used resource", result.isFailure)
            } else {
                val modification = PageModification(0, redactions = listOf(
                    RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")
                ))
                val result = PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to modification))
                assertTrue("Editor must reject private data shared with a used resource", result.isFailure)
            }
            assertArrayEquals("Rejected export must preserve every existing destination byte", existing, output.readBytes())
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun exportArchivedFixture(attachment: Boolean, expectedSha256: String) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val name = if (attachment) "attachment-alias-input.pdf" else "piece-info-alias-input.pdf"
        val input = InstrumentationRegistry.getInstrumentation().context.assets.open("security/sc10/$name").use { it.readBytes() }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("Packaged input must be byte-identical to the retained audit fixture", expectedSha256, sha256)
        val source = File.createTempFile("sc10_archived_source_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc10_archived_output_", ".pdf", context.cacheDir)
        try {
            source.writeBytes(input)
            PDDocument.load(source).use { doc ->
                assertEquals(1, doc.numberOfPages)
                val alias = doc.documentCatalog.cosObject.getDictionaryObject(alternatePayload) as COSStream
                assertNull(alias.getDictionaryObject(COSName.TYPE))
                assertEquals(privatePayload, alias.createInputStream().use { String(it.readBytes(), Charsets.UTF_8) })
            }
            val modification = PageModification(0, redactions = listOf(
                RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")
            ))
            assertTrue("Editor must export the exact retained audit input", PdfEditor.exportModifiedPdf(
                context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to modification)
            ).getOrThrow())
            assertFalse("Saved PDF bytes must omit the retained private payload", String(output.readBytes(), Charsets.ISO_8859_1).contains(privatePayload))
            PDDocument.load(output).use { saved ->
                assertEquals(1, saved.numberOfPages)
                assertNull(saved.documentCatalog.cosObject.getDictionaryObject(alternatePayload))
                assertNull(saved.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName(if (attachment) "AF" else "PieceInfo")))
                assertNoPrivateData(saved)
            }
            val original = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(source), 0, 300))
            val redacted = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 0, 300))
            try {
                assertEquals(original.width, redacted.width)
                assertEquals(original.height, redacted.height)
                assertWhite(original.getPixel(original.width / 2, original.height / 2), "Retained input must have an unpainted page center")
                for (y in 1 until redacted.height - 1 step 19) for (x in 1 until redacted.width - 1 step 23) {
                    assertBlack(redacted.getPixel(x, y), "Archived fixture must have an opaque Editor redaction at ($x,$y)")
                }
            } finally { original.recycle(); redacted.recycle() }
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun writeFixture(source: File, attachment: Boolean) {
        PDDocument().use { doc ->
            val privatePage = PDPage(PDRectangle(300f, 200f)).also(doc::addPage)
            val publicPage = PDPage(PDRectangle(300f, 200f)).also(doc::addPage)
            PDPageContentStream(doc, privatePage).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                it.newLineAtOffset(30f, 100f); it.showText(privateText); it.endText()
            }
            PDPageContentStream(doc, publicPage).use {
                it.beginText(); it.setFont(PDType1Font.COURIER, 12f)
                it.newLineAtOffset(30f, 100f); it.showText(publicText); it.endText()
                it.setNonStrokingColor(0, 180, 0)
                it.addRect(240f, 20f, 30f, 30f); it.fill()
            }
            publicPage.annotations.add(PDAnnotationLink().apply {
                rectangle = PDRectangle(10f, 10f, 100f, 20f)
                destination = PDPageFitDestination().apply { page = privatePage }
            })
            // Keep the archived probe's descendant topology and deliberately omit /Type on the stream.
            val payload = doc.document.createCOSStream().apply {
                setString(COSName.getPDFName("PrivateNote"), privatePayload)
                createOutputStream().use { it.write(privatePayload.toByteArray(Charsets.UTF_8)) }
            }
            val catalog = doc.documentCatalog.cosObject
            if (attachment) {
                val specification = COSDictionary().apply {
                    setName(COSName.TYPE, "Filespec")
                    setString(COSName.F, "private.txt")
                    setItem(COSName.getPDFName("EF"), COSDictionary().apply { setItem(COSName.F, payload) })
                }
                catalog.setItem(COSName.getPDFName("AF"), COSArray().apply { add(specification) })
            } else {
                catalog.setItem(COSName.getPDFName("PieceInfo"), COSDictionary().apply {
                    setItem(COSName.getPDFName("PrivateApp"), COSDictionary().apply {
                        setString(COSName.getPDFName("LastModified"), "D:20261021000000Z")
                        setItem(COSName.getPDFName("Private"), payload)
                    })
                })
            }
            catalog.setItem(alternatePayload, payload)
            doc.save(source)
        }
    }

    private fun assertInputContainsAlias(source: File, attachment: Boolean) {
        PDDocument.load(source).use { doc ->
            val catalog = doc.documentCatalog.cosObject
            val alias = catalog.getDictionaryObject(alternatePayload) as COSStream
            val carrierPayload = if (attachment) {
                val specifications = catalog.getDictionaryObject(COSName.getPDFName("AF")) as COSArray
                val specification = specifications.getObject(0) as COSDictionary
                val embedded = specification.getDictionaryObject(COSName.getPDFName("EF")) as COSDictionary
                embedded.getDictionaryObject(COSName.F)
            } else {
                val pieceInfo = catalog.getDictionaryObject(COSName.getPDFName("PieceInfo")) as COSDictionary
                val application = pieceInfo.getDictionaryObject(COSName.getPDFName("PrivateApp")) as COSDictionary
                application.getDictionaryObject(COSName.getPDFName("Private"))
            }
            assertSame("Serialized input must retain the descendant alias", carrierPayload, alias)
            assertNull("Fixture must not rely on an EmbeddedFile stream type", alias.getDictionaryObject(COSName.TYPE))
            assertEquals(privatePayload, alias.createInputStream().use { String(it.readBytes(), Charsets.UTF_8) })
            assertEquals(privatePayload, alias.getString(COSName.getPDFName("PrivateNote")))
            assertTrue(PDFTextStripper().getText(doc).contains(privateText))
        }
    }

    private fun assertSavedDocument(output: File, attachment: Boolean) {
        val raw = String(output.readBytes(), Charsets.ISO_8859_1)
        assertFalse("Saved PDF bytes must omit private carrier data", raw.contains(privatePayload))
        assertFalse("Saved PDF bytes must omit the original page text", raw.contains(privateText))
        PDDocument.load(output).use { saved ->
            assertEquals("Sanitization must preserve the independent page", 2, saved.numberOfPages)
            val text = PDFTextStripper().getText(saved)
            assertFalse("Page extraction must omit redacted text", text.contains(privateText))
            assertTrue("Independent page must retain vector text", text.contains(publicText))
            val catalog = saved.documentCatalog.cosObject
            assertNull("Private payload alias must be detached", catalog.getDictionaryObject(alternatePayload))
            assertNull("Private carrier must be detached", catalog.getDictionaryObject(
                COSName.getPDFName(if (attachment) "AF" else "PieceInfo")
            ))
            assertNoPrivateData(saved)
            val link = saved.getPage(1).annotations.single() as PDAnnotationLink
            val destination = link.destination as PDPageFitDestination
            assertSame("Internal navigation must target the sanitized page", saved.getPage(0).cosObject, destination.page.cosObject)
        }
    }

    private fun assertNoPrivateData(document: PDDocument) {
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        fun visit(raw: COSBase?, depth: Int) {
            if (raw == null || !seen.add(raw)) return
            assertTrue("Saved object graph must remain bounded", depth <= 128 && seen.size <= 100000)
            if (raw is COSObject) {
                visit(raw.`object`, depth + 1)
                return
            }
            if (raw is COSString) {
                assertFalse("Private carrier strings must not survive", raw.string.contains(privatePayload))
                assertFalse("Original private text strings must not survive", raw.string.contains(privateText))
            }
            if (raw is COSStream) {
                val decoded = raw.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
                assertFalse("Private descendant streams must not survive", decoded.contains(privatePayload))
                assertFalse("Original page streams must not survive", decoded.contains(privateText))
            }
            when (raw) {
                is COSDictionary -> raw.keySet().toList().forEach { visit(raw.getItem(it), depth + 1) }
                is COSArray -> (0 until raw.size()).forEach { visit(raw.get(it), depth + 1) }
            }
        }
        visit(document.document.trailer, 0)
        // Also inspect serialized pooled objects, including anything outside normal catalog traversal.
        document.document.objects.forEach { visit(it, 0) }
    }

    private suspend fun assertNativePixels(context: Context, source: File, output: File, search: Boolean) {
        val original = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(source), 0, 600))
        val redacted = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 0, 600))
        try {
            assertEquals(original.width, redacted.width)
            assertEquals(original.height, redacted.height)
            if (search) {
                var left = original.width; var top = original.height; var right = 0; var bottom = 0
                for (y in 0 until original.height) for (x in 0 until original.width) {
                    if (Color.red(original.getPixel(x, y)) < 100) {
                        left = minOf(left, x); right = maxOf(right, x)
                        top = minOf(top, y); bottom = maxOf(bottom, y)
                    }
                }
                assertTrue("Input must visibly contain private glyphs", right > left && bottom > top)
                for (y in top + 1 until bottom step 2) for (x in left + 1 until right step 3) {
                    assertBlack(redacted.getPixel(x, y), "Search blackout must cover original glyphs at ($x,$y)")
                }
                val outsideX = original.width * 9 / 10
                val outsideY = original.height * 9 / 10
                assertWhite(original.getPixel(outsideX, outsideY), "Source control outside the glyphs must be white")
                assertWhite(redacted.getPixel(outsideX, outsideY), "Search must preserve the page background outside the redaction")
            } else {
                for (y in redacted.height / 4 until redacted.height * 3 / 4 step 19) {
                    for (x in redacted.width / 4 until redacted.width * 3 / 4 step 23) {
                        assertBlack(redacted.getPixel(x, y), "Editor redaction must remain opaque black")
                    }
                }
            }
        } finally { original.recycle(); redacted.recycle() }

        val originalPublic = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(source), 1, 300))
        val savedPublic = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 1, 300))
        try {
            assertEquals(originalPublic.width, savedPublic.width)
            assertEquals(originalPublic.height, savedPublic.height)
            for (y in 0 until originalPublic.height) for (x in 0 until originalPublic.width) {
                assertEquals("Independent page pixels must survive at ($x,$y)", originalPublic.getPixel(x, y), savedPublic.getPixel(x, y))
            }
        } finally { originalPublic.recycle(); savedPublic.recycle() }
    }

    private fun assertBlack(color: Int, message: String) {
        assertTrue(message, Color.alpha(color) == 255 && Color.red(color) < 20 && Color.green(color) < 20 && Color.blue(color) < 20)
    }

    private fun assertWhite(color: Int, message: String) {
        assertTrue(message, Color.alpha(color) == 255 && Color.red(color) > 240 && Color.green(color) > 240 && Color.blue(color) > 240)
    }

    @Test(timeout = 180000) fun pieceInfoDescendantAliasCannotSurviveEditorExport() = runFixture(attachment = false, search = false)
    @Test(timeout = 180000) fun untypedAttachmentDescendantAliasCannotSurviveEditorExport() = runFixture(attachment = true, search = false)
    @Test(timeout = 180000) fun pieceInfoDescendantAliasCannotSurviveSecureSearchExport() = runFixture(attachment = false, search = true)
    @Test(timeout = 180000) fun untypedAttachmentDescendantAliasCannotSurviveSecureSearchExport() = runFixture(attachment = true, search = true)
    @Test(timeout = 180000) fun pieceInfoAliasToUsedFontRejectsEditorWithoutOverwritingDestination() = rejectUsedResourceAlias(search = false)
    @Test(timeout = 180000) fun pieceInfoAliasToUsedFontRejectsSecureSearchWithoutOverwritingDestination() = rejectUsedResourceAlias(search = true)
    @Test(timeout = 180000) fun retainedPieceInfoAuditFixtureCannotLeakThroughEditorExport() = exportArchivedFixture(
        attachment = false, expectedSha256 = "057469d492abfdce4e496d102319315c113ca7f15c29f9c9667ba4292aa1bd67")
    @Test(timeout = 180000) fun retainedAttachmentAuditFixtureCannotLeakThroughEditorExport() = exportArchivedFixture(
        attachment = true, expectedSha256 = "8c7fd137e1dd0b83b26c240b6cfee6bd7e0e2bca97a5e53a4fcba6c8286f77bf")
}
