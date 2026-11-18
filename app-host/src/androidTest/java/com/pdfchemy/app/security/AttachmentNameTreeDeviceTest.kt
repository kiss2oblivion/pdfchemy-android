package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.AttachmentListContract
import com.pdfchemy.app.logic.PdfGateway
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.filespecification.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AttachmentNameTreeDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owned = mutableListOf<File>()
    @Before fun init() { PDFBoxResourceLoader.init(context) }
    @After fun cleanup() { owned.forEach { it.delete() } }
    private fun file(prefix: String = "attachment_tree_") = File.createTempFile(prefix, ".bin", context.cacheDir).also(owned::add)
    private fun fixture(): File = file().also { source ->
        PDDocument().use { document ->
            document.addPage(PDPage())
            val first = PDComplexFileSpecification().apply { this.file = "legacy.txt"; embeddedFile = PDEmbeddedFile(document, "FIRST".byteInputStream()) }
            val second = PDComplexFileSpecification().apply { fileUnicode = "документ.txt"; embeddedFileUnicode = PDEmbeddedFile(document, "UNICODE".byteInputStream()) }
            val firstLeaf = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("legacy-key" to first) }
            val secondLeaf = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("子" to second) }
            val root = PDEmbeddedFilesNameTreeNode().apply { kids = listOf(firstLeaf, PDEmbeddedFilesNameTreeNode().apply { kids = listOf(secondLeaf) }) }
            document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply { embeddedFiles = root }
            document.save(source)
        }
    }
    private suspend fun inventory(source: File) = PdfGateway.executeEngineTyped<AttachmentListContract>(context,
        "ATTACHMENT_LIST", Uri.fromFile(source), null, "{}").attachments
    private suspend fun extract(source: File, id: String): ByteArray {
        val target = file("attachment_extract_")
        PdfGateway.executeEngine(context, "ATTACHMENT_EXTRACT", Uri.fromFile(source), Uri.fromFile(target), JSONObject().put("attachmentId", id).toString())
        return target.readBytes()
    }
    @Test fun inventoryContainsEveryChildAndUnicodeFilename() = runBlocking<Unit> {
        val source = fixture(); val original = source.readBytes()
        val items = inventory(source)
        assertEquals(setOf("legacy-key", "子"), items.map { it.attachmentId }.toSet())
        assertEquals("документ.txt", items.single { it.attachmentId == "子" }.name)
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun unicodeOnlyChildPayloadCanBeExtracted() = runBlocking<Unit> {
        val source = fixture(); val original = source.readBytes()
        assertEquals("UNICODE", String(extract(source, "子")))
        assertEquals("FIRST", String(extract(source, "legacy-key")))
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun removingAChildPreservesTheOtherBranch() = runBlocking<Unit> {
        val source = fixture(); val original = source.readBytes(); val output = file()
        PdfGateway.executeEngine(context, "ATTACHMENT_REMOVE", Uri.fromFile(source), Uri.fromFile(output), JSONObject().put("attachmentId", "子").toString())
        assertEquals(listOf("legacy-key"), inventory(output).map { it.attachmentId })
        assertEquals("FIRST", String(extract(output, "legacy-key")))
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun embeddingPreservesChildrenAndKeyCollisionAndUsesValidatedSize() = runBlocking<Unit> {
        val source = fixture(); val original = source.readBytes(); val output = file()
        val extra = file().apply { writeText("NEW_FILE") }
        val params = JSONObject().put("fileName", "legacy-key").put("fileSize", Long.MAX_VALUE)
        PdfGateway.executeEngineExtra(context, "ATTACHMENT_EMBED", Uri.fromFile(source), Uri.fromFile(output), Uri.fromFile(extra), params.toString())
        val items = inventory(output)
        assertEquals(3, items.size)
        assertEquals("FIRST", String(extract(output, "legacy-key")))
        assertEquals("UNICODE", String(extract(output, "子")))
        val added = items.single { it.name == "legacy-key" }
        assertNotEquals("legacy-key", added.attachmentId)
        assertEquals(extra.length(), added.sizeBytes)
        assertEquals("NEW_FILE", String(extract(output, added.attachmentId)))
        assertArrayEquals(original, source.readBytes())
        assertEquals("NEW_FILE", extra.readText())
    }
    @Test(timeout = 60000) fun cyclicTreeFailsWithoutOpeningExistingDestination() = runBlocking<Unit> {
        val source = file(); val output = file().apply { writeText("EXISTING_DESTINATION") }
        PDDocument().use { document ->
            document.addPage(PDPage())
            val root = PDEmbeddedFilesNameTreeNode()
            root.cosObject.setItem(COSName.KIDS, COSArray().apply { add(root.cosObject) })
            document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply { embeddedFiles = root }
            document.save(source)
        }
        val original = source.readBytes()
        val failure = runCatching {
            PdfGateway.executeEngine(context, "ATTACHMENT_EXTRACT", Uri.fromFile(source), Uri.fromFile(output), JSONObject().put("attachmentId", "missing").toString())
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.toString().contains("attachment name tree"))
        assertEquals("EXISTING_DESTINATION", output.readText())
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun longUnicodeIdentifierPreservesTheExistingIpcCharacterLimit() = runBlocking<Unit> {
        val source = file(); val id = "子".repeat(512)
        PDDocument().use { document ->
            document.addPage(PDPage())
            val spec = PDComplexFileSpecification().apply { fileUnicode = "unicode.txt"; embeddedFileUnicode = PDEmbeddedFile(document, "LONG_ID".byteInputStream()) }
            val leaf = PDEmbeddedFilesNameTreeNode().apply { names = mapOf(id to spec) }
            val root = PDEmbeddedFilesNameTreeNode().apply { kids = listOf(leaf) }
            document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply { embeddedFiles = root }
            document.save(source)
        }
        assertEquals(id, inventory(source).single().attachmentId)
        assertEquals("LONG_ID", String(extract(source, id)))
    }
}
