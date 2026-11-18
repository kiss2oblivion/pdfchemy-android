package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.filespecification.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AttachmentNameTreeTest {
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }
    private fun name(value: String) = COSName.getPDFName(value)
    private fun spec(doc: PDDocument, filename: String, content: String, unicode: Boolean = false) = PDComplexFileSpecification().apply {
        if (unicode) {
            fileUnicode = filename; embeddedFileUnicode = PDEmbeddedFile(doc, content.byteInputStream())
        } else { file = filename; embeddedFile = PDEmbeddedFile(doc, content.byteInputStream()) }
    }
    private fun fixture(doc: PDDocument): PDEmbeddedFilesNameTreeNode {
        doc.addPage(PDPage())
        val first = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("first" to spec(doc, "first.txt", "FIRST")) }
        val second = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("second" to spec(doc, "second.txt", "SECOND")) }
        val root = PDEmbeddedFilesNameTreeNode().apply {
            kids = listOf(first, PDEmbeddedFilesNameTreeNode().apply { kids = listOf(second) })
            cosObject.setString(name("ApplicationNote"), "KEEP_ROOT_METADATA")
        }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = root }
        return root
    }
    private fun list(doc: PDDocument) = JSONArray(PdfAttachmentEngineWorker.listAttachments(doc))
    private fun payload(doc: PDDocument, id: String) = requireNotNull(PdfAttachmentEngineWorker.embeddedPayload(
        PdfAttachmentEngineWorker.resolveAttachment(doc, JSONObject().put("attachmentId", id)))).createInputStream().use { String(it.readBytes()) }
    private fun saved(doc: PDDocument, check: (PDDocument) -> Unit) {
        val bytes = ByteArrayOutputStream().also(doc::save).toByteArray()
        PDDocument.load(bytes).use(check)
    }
    @Test fun listAndLookupTraverseEveryChildAfterSaving() {
        PDDocument().use { doc -> fixture(doc); saved(doc) {
            assertEquals(2, list(it).length()); assertEquals("FIRST", payload(it, "first")); assertEquals("SECOND", payload(it, "second"))
        } }
    }
    @Test fun removingChildEntryPreservesOtherBranchesAndRootMetadata() {
        PDDocument().use { doc ->
            fixture(doc)
            PdfAttachmentEngineWorker.removeAttachmentFromDocument(doc, JSONObject().put("attachmentId", "first"))
            saved(doc) {
                assertEquals(1, list(it).length()); assertEquals("SECOND", payload(it, "second"))
                assertEquals("KEEP_ROOT_METADATA", it.documentCatalog.names.embeddedFiles.cosObject.getString(name("ApplicationNote")))
                assertTrue(runCatching { payload(it, "first") }.isFailure)
            }
        }
    }
    @Test fun embeddingPreservesAllChildrenAndDoesNotOverwriteAKeyCollision() {
        PDDocument().use { doc ->
            fixture(doc)
            val id = PdfAttachmentEngineWorker.addAttachmentToDocument(doc, "first", "EXTRA".byteInputStream(), 5)
            assertNotEquals("first", id)
            saved(doc) {
                assertEquals(3, list(it).length()); assertEquals("FIRST", payload(it, "first")); assertEquals("SECOND", payload(it, "second")); assertEquals("EXTRA", payload(it, id))
            }
        }
    }
    @Test fun unicodeOnlyPayloadAndFilenameAreSupported() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply {
                embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("子" to spec(doc, "документ.txt", "UNICODE_PAYLOAD", true)) }
            }
            saved(doc) {
                assertEquals("документ.txt", list(it).getJSONObject(0).getString("name")); assertEquals("UNICODE_PAYLOAD", payload(it, "子"))
            }
        }
    }
    @Test fun unicodePayloadTakesPrecedenceOverLegacyFallback() {
        PDDocument().use { doc ->
            val root = fixture(doc)
            val value = spec(doc, "unicode.txt", "UNICODE", true).apply { embeddedFile = PDEmbeddedFile(doc, "LEGACY".byteInputStream()) }
            root.kids = null; root.names = mapOf("selected" to value)
            assertEquals("UNICODE", payload(doc, "selected"))
        }
    }
    @Test fun duplicateIdentifiersAcrossChildrenFailBeforeInputConsumptionOrMutation() {
        PDDocument().use { doc ->
            val root = fixture(doc)
            root.kids = listOf(
                PDEmbeddedFilesNameTreeNode().apply { names = mapOf("duplicate" to spec(doc, "a.txt", "A")) },
                PDEmbeddedFilesNameTreeNode().apply { names = mapOf("duplicate" to spec(doc, "b.txt", "B")) })
            var reads = 0
            val input = object : java.io.InputStream() { override fun read(): Int { reads++; return -1 } }
            assertTrue(runCatching { PdfAttachmentEngineWorker.addAttachmentToDocument(doc, "extra", input, 0) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, reads); assertSame(root.cosObject, doc.documentCatalog.names.embeddedFiles.cosObject)
        }
    }
    @Test fun cyclicAndExcessivelyDeepTreesFailClosed() {
        PDDocument().use { doc ->
            val root = fixture(doc)
            root.cosObject.setItem(COSName.KIDS, COSArray().apply { add(root.cosObject) })
            assertTrue(runCatching { list(doc) }.exceptionOrNull() is IllegalArgumentException)
            var nested = COSDictionary()
            repeat(140) { nested = COSDictionary().apply { setItem(COSName.KIDS, COSArray().apply { add(nested) }) } }
            doc.documentCatalog.names.embeddedFiles = PDEmbeddedFilesNameTreeNode(nested)
            assertTrue(runCatching { list(doc) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun mixedChildrenAndNamesAndMalformedLeavesAreRejected() {
        PDDocument().use { doc ->
            val root = fixture(doc)
            root.cosObject.setItem(COSName.NAMES, COSArray())
            assertTrue(runCatching { list(doc) }.exceptionOrNull() is IllegalArgumentException)
            root.cosObject.removeItem(COSName.KIDS)
            root.cosObject.setItem(COSName.NAMES, COSArray().apply { add(COSString("unpaired")) })
            assertTrue(runCatching { list(doc) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun documentHierarchyAliasIsRejectedWithoutChangingThePage() {
        PDDocument().use { doc ->
            val root = fixture(doc)
            doc.documentCatalog.names.embeddedFiles = PDEmbeddedFilesNameTreeNode(doc.getPage(0).cosObject)
            assertTrue(runCatching { PdfAttachmentEngineWorker.removeAttachmentFromDocument(doc, JSONObject().put("attachmentId", "first")) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, doc.numberOfPages)
        }
    }
    @Test fun aggregateEntryQuotaAppliesAcrossChildren() {
        PDDocument().use { doc ->
            val root = fixture(doc)
            root.kids = (0..500).map { index -> PDEmbeddedFilesNameTreeNode().apply { names = mapOf("id-$index" to PDComplexFileSpecification().apply { file = "a.txt" }) } }
            assertTrue(runCatching { list(doc) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
    @Test fun multibyteIdentifierRetainsTheExistingCharacterBasedLimit() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val id = "子".repeat(512)
            doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply {
                embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf(id to spec(doc, "unicode.txt", "LONG_ID", true)) }
            }
            saved(doc) { assertEquals(id, list(it).getJSONObject(0).getString("attachmentId")); assertEquals("LONG_ID", payload(it, id)) }
        }
    }
}
