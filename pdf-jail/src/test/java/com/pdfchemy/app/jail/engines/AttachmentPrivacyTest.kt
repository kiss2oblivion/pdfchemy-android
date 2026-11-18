package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.filespecification.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AttachmentPrivacyTest {
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }
    private val marker = "PRIVATE_ATTACHMENT_883279"
    private fun name(value: String) = COSName.getPDFName(value)
    private fun fixture(doc: PDDocument): PDEmbeddedFile {
        doc.addPage(PDPage())
        val stream = PDEmbeddedFile(doc, marker.byteInputStream())
        val spec = PDComplexFileSpecification().apply { file = "private.txt"; embeddedFile = stream }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply {
            embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("private" to spec) }
        }
        doc.documentCatalog.cosObject.setItem(name("AlternatePayload"), stream)
        return stream
    }
    private fun clean(doc: PDDocument, metadata: Boolean = false, attachments: Boolean = true) =
        PdfSanitizerEngineWorker.cleanDocument(doc, true, true, metadata, attachments)
    private fun output(doc: PDDocument, check: (PDDocument) -> Unit) {
        val bytes = ByteArrayOutputStream().also(doc::save).toByteArray()
        PDDocument.load(bytes).use(check)
    }
    private fun absent(doc: PDDocument) = output(doc) { saved ->
        PdfSanitizerEngineWorker.validateCleanDocument(saved, true, true, false, true)
        assertNull(saved.documentCatalog.cosObject.getItem(name("AlternatePayload")))
        for (obj in saved.document.objects) (obj.`object` as? COSStream)?.let {
            assertFalse(it.createInputStream().use { input -> String(input.readBytes()) }.contains(marker))
        }
    }
    @Test fun aliasedEmbeddedStreamIsRemovedAndOriginalCountIsReported() {
        PDDocument().use { doc -> fixture(doc); assertEquals(1, clean(doc).attachments); absent(doc) }
    }
    @Test fun keepOptionPreservesExactPayloadAndAliases() {
        PDDocument().use { doc ->
            fixture(doc)
            clean(doc, attachments = false)
            output(doc) { saved ->
                val stream = saved.documentCatalog.cosObject.getDictionaryObject(name("AlternatePayload")) as COSStream
                assertEquals(marker, stream.createInputStream().use { String(it.readBytes()) })
                assertEquals(1, ActiveContentScrubber.inspect(saved).attachments)
            }
        }
    }
    @Test fun untypedEmbeddedStreamIsRemovedThroughItsFileSpecification() {
        PDDocument().use { doc ->
            fixture(doc).cosObject.removeItem(COSName.TYPE)
            clean(doc); absent(doc)
        }
    }
    @Test fun privateDescendantAliasesAndCyclesAreDetached() {
        PDDocument().use { doc ->
            val stream = fixture(doc)
            val private = COSString(marker)
            val nested = COSDictionary().apply { setItem(name("Private"), private); setItem(name("Self"), this) }
            stream.cosObject.setItem(name("Nested"), nested)
            doc.documentCatalog.cosObject.setItem(name("AlternateString"), private)
            clean(doc)
            assertNull(doc.documentCatalog.cosObject.getItem(name("AlternateString")))
            absent(doc)
        }
    }
    @Test fun attachmentPurgePreservesUnselectedMetadataAndSourceIdentifier() {
        PDDocument().use { doc ->
            fixture(doc)
            doc.documentInformation.author = "KEEP_AUTHOR"
            val id = COSArray().apply { add(COSString("KEEP_ID")); add(COSString("KEEP_ID")) }
            doc.document.trailer.setItem(COSName.ID, id)
            clean(doc)
            assertSame(id, doc.document.trailer.getItem(COSName.ID))
            output(doc) { assertEquals("KEEP_AUTHOR", it.documentInformation.author) }
            absent(doc)
        }
    }
    @Test fun combinedPurgeRemovesAliasesSharedBySelectedMetadataAndAttachments() {
        PDDocument().use { doc ->
            val stream = fixture(doc)
            doc.getPage(0).cosObject.setItem(COSName.METADATA, stream)
            clean(doc, metadata = true)
            absent(doc)
            output(doc) { PdfSanitizerEngineWorker.validateCleanDocument(it, true, true, true, true) }
        }
    }
    @Test fun liveContentAliasFailsBeforeAnyMutation() {
        PDDocument().use { doc ->
            val stream = fixture(doc)
            doc.getPage(0).cosObject.setItem(COSName.CONTENTS, stream)
            assertTrue(runCatching { clean(doc) }.isFailure)
            assertTrue(doc.documentCatalog.names.embeddedFiles.names.containsKey("private"))
            assertSame(stream.cosObject, doc.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS))
        }
    }
    @Test fun savedValidationRejectsAnOrphanTypedPayloadEvenWithoutFileSpec() {
        PDDocument().use { doc ->
            val stream = fixture(doc)
            doc.documentCatalog.names.embeddedFiles = null
            assertEquals(0, ActiveContentScrubber.inspect(doc).attachments)
            assertTrue(runCatching { PdfSanitizerEngineWorker.validateCleanDocument(doc, true, true, false, true) }.isFailure)
            clean(doc)
            absent(doc)
        }
    }
}
