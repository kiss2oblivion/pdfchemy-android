package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.filespecification.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AttachmentSelectionTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun initialize() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }
    private fun fixture(duplicateNames: Boolean = false): File {
        val file = temp.newFile()
        PDDocument().use { document ->
            document.addPage(PDPage())
            fun spec(name: String, payload: String) = PDComplexFileSpecification().apply {
                this.file = name
                embeddedFile = PDEmbeddedFile(document, payload.byteInputStream()).apply { subtype = "text/plain" }
            }
            document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply {
                embeddedFiles = PDEmbeddedFilesNameTreeNode().apply {
                    names = linkedMapOf(
                        "collision.txt" to spec(if (duplicateNames) "collision.txt" else "other.txt", "first payload"),
                        "z-target" to spec("collision.txt", "selected payload")
                    )
                }
            }
            document.save(file)
        }
        return file
    }
    private fun list(file: File): JSONArray = PDDocument.load(file).use {
        JSONArray(PdfAttachmentEngineWorker.listAttachments(it))
    }
    private fun extract(file: File, item: JSONObject): String = PDDocument.load(file).use { document ->
        val spec = PdfAttachmentEngineWorker.resolveAttachment(document, JSONObject()
            .put("attachmentId", item.optString("attachmentId", item.getString("name")))
            .put("attachmentName", item.getString("name")))
        spec.embeddedFile.createInputStream().use { String(it.readBytes()) }
    }
    @Test fun filenameCannotAliasAnotherAttachmentKey() {
        val file = fixture()
        val items = list(file)
        val selected = (0 until items.length()).map(items::getJSONObject).single { it.getString("name") == "collision.txt" }
        assertEquals("selected payload", extract(file, selected))
    }
    @Test fun duplicateFilenamesHaveDifferentIdentifiersAndPayloads() {
        val file = fixture(true)
        val items = list(file)
        assertEquals(2, items.length())
        assertNotEquals(items.getJSONObject(0).getString("attachmentId"), items.getJSONObject(1).getString("attachmentId"))
        assertEquals(setOf("first payload", "selected payload"), (0 until items.length()).map { extract(file, items.getJSONObject(it)) }.toSet())
    }
    @Test fun exactIdentifierControlsExtractionEvenWithConflictingDisplayName() {
        PDDocument.load(fixture()).use { document ->
            val spec = PdfAttachmentEngineWorker.resolveAttachment(document,
                JSONObject().put("attachmentId", "z-target").put("attachmentName", "other.txt"))
            assertEquals("selected payload", spec.embeddedFile.createInputStream().use { String(it.readBytes()) })
        }
    }
    @Test fun removalUsesTheSameIdentityAndPreservesOtherAttachments() {
        PDDocument.load(fixture()).use { document ->
            PdfAttachmentEngineWorker.removeAttachmentFromDocument(document, JSONObject().put("attachmentId", "z-target"))
            val saved = temp.newFile()
            document.save(saved)
            val remaining = list(saved)
            assertEquals(1, remaining.length())
            assertEquals("collision.txt", remaining.getJSONObject(0).getString("attachmentId"))
            assertEquals("first payload", extract(saved, remaining.getJSONObject(0)))
        }
    }
    @Test fun missingOrInvalidIdentifiersCannotFallBackToFilenames() {
        PDDocument.load(fixture()).use { document ->
            for (request in listOf(JSONObject().put("attachmentName", "collision.txt"),
                JSONObject().put("attachmentId", "missing"), JSONObject().put("attachmentId", 1))) {
                assertNotNull(runCatching { PdfAttachmentEngineWorker.resolveAttachment(document, request) }.exceptionOrNull())
                assertNotNull(runCatching { PdfAttachmentEngineWorker.removeAttachmentFromDocument(document, request) }.exceptionOrNull())
                assertEquals(2, JSONArray(PdfAttachmentEngineWorker.listAttachments(document)).length())
            }
        }
    }
    @Test fun absentFilenameUsesKeyForDisplayAndLookup() {
        PDDocument.load(fixture()).use { document ->
            document.documentCatalog.names.embeddedFiles.names["z-target"]!!.file = null
            val item = JSONArray(PdfAttachmentEngineWorker.listAttachments(document)).getJSONObject(1)
            assertEquals("z-target", item.getString("attachmentId"))
            assertEquals("z-target", item.getString("name"))
        }
    }

    @Test fun omittedEmbeddedSizeUsesNonnegativeUnknownPlaceholder() {
        val items = list(fixture())
        for (i in 0 until items.length()) assertEquals(0L, items.getJSONObject(i).getLong("sizeBytes"))
    }

}
