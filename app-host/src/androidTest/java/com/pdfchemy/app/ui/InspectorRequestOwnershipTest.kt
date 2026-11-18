package com.pdfchemy.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.OutputPolicy
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.*
import com.tom_roush.pdfbox.pdmodel.common.filespecification.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/** Real inspection engines plus controlled document changes and delayed SAF callbacks. */
class InspectorRequestOwnershipTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private enum class Tool { METADATA, FONTS, ATTACHMENTS }
    private inner class Harness(val tool: Tool, invalidB: Boolean = false, embeddedFont: Boolean = false) : AutoCloseable {
        private val resolver = app.contentResolver
        private val control = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        private val tree = DocumentsContract.buildTreeDocumentUri(control.authority!!, "root")
        private val settings = app.getSharedPreferences("shrinkpdf_settings", Application.MODE_PRIVATE)
        private val oldFolder = settings.getString(OutputPolicy.DIRECTORY_KEY, null)
        val aFile = File.createTempFile("inspection_a_", ".pdf", app.cacheDir)
        val bFile = File.createTempFile("inspection_b_", ".pdf", app.cacheDir)
        val extraFile = File.createTempFile("attachment_extra_", ".txt", app.cacheDir).apply { writeText("EXTRA_PAYLOAD") }
        val backs = AtomicInteger()
        val selected = mutableStateOf<Uri?>(Uri.fromFile(aFile))
        private val visible = mutableStateOf(true)
        private val store = ViewModelStore()
        var pendingCode: Int? = null
        var extraCode: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                when (contract) {
                    is ActivityResultContracts.OpenDocumentTree -> pendingCode = code
                    is ActivityResultContracts.OpenDocument -> extraCode = code
                    else -> error("Unexpected picker contract")
                }
            }
        }
        init {
            PDFBoxResourceLoader.init(app)
            fun source(file: File, suffix: String) {
                PDDocument().use { document ->
                    val page = PDPage().also(document::addPage)
                    document.documentInformation.author = "AUTHOR_$suffix"
                    val font = if (embeddedFont) PDFBoxResourceLoader.getStream("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf").use { PDType0Font.load(document, it) }
                        else PDType1Font.HELVETICA
                    PDPageContentStream(document, page).use { it.beginText(); it.setFont(font, 12f); it.newLineAtOffset(40f, 50f); it.showText("KEEP_$suffix"); it.endText() }
                    val spec = PDComplexFileSpecification().apply { this.file = "$suffix.txt"; embeddedFile = PDEmbeddedFile(document, "PAYLOAD_$suffix".byteInputStream()) }
                    document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply {
                        embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("same-key" to spec) }
                    }
                    document.save(file)
                }
            }
            source(aFile, "A")
            if (invalidB) bFile.writeText("%PDF-invalid") else source(bFile, "B")
            resolver.call(control, "configure", null, null); resolver.call(control, "release", null, null)
            settings.edit().remove(OutputPolicy.DIRECTORY_KEY).commit()
            lateinit var model: MainViewModel
            compose.runOnUiThread { model = MainViewModel(app); store.put("inspection-owner", model) }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme {
                        if (visible.value) when (tool) {
                            Tool.METADATA -> MetadataSanitizerScreen(model, selected.value, { backs.incrementAndGet() })
                            Tool.FONTS -> FontInspectorScreen(model, selected.value, {})
                            Tool.ATTACHMENTS -> AttachmentManagerScreen(model, selected.value, {})
                        }
                    }
                }
            }
        }
        fun chooseB() = compose.runOnUiThread { selected.value = Uri.fromFile(bFile) }
        fun deliver() = compose.runOnUiThread { registry.dispatchResult(requireNotNull(pendingCode), Activity.RESULT_OK, Intent().setData(tree)) }
        fun deliverExtra() = compose.runOnUiThread { registry.dispatchResult(requireNotNull(extraCode), Activity.RESULT_OK, Intent().setData(Uri.fromFile(extraFile))) }
        fun outputs() = DocumentFile.fromTreeUri(app, tree)!!.listFiles().filter { DocumentsContract.getDocumentId(it.uri) != "existing" }
        fun read(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        fun published(): DocumentFile {
            compose.waitUntil(20_000) {
                outputs().singleOrNull()?.let { !OutputPolicy.isFreshCopy(it.uri) &&
                    !com.pdfchemy.app.security.PublicationCoordinator.hasActiveWriter(it.uri) && it.length() > 0L } == true
            }
            return outputs().single()
        }
        fun abandoned() = requireNotNull(resolver.call(control, "state", null, null)).let { it.getInt("createdCount") == 1 && it.getInt("deletedCount") == 1 }
        override fun close() {
            compose.runOnUiThread { visible.value = false }; compose.waitForIdle(); compose.runOnUiThread { store.clear() }
            DocumentFile.fromTreeUri(app, tree)!!.listFiles().filter { DocumentsContract.getDocumentId(it.uri) != "existing" }.forEach { OutputPolicy.unregisterFreshCopy(it.uri); it.delete() }
            settings.edit().apply { if (oldFolder == null) remove(OutputPolicy.DIRECTORY_KEY) else putString(OutputPolicy.DIRECTORY_KEY, oldFolder) }.commit()
            aFile.delete(); bFile.delete(); extraFile.delete()
        }
    }
    private fun waitText(value: String, substring: Boolean = false) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(value, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun failedMetadataInspectionClearsPreviousFields() = failedInspection(Tool.METADATA, "AUTHOR_A")
    @Test fun failedFontInspectionClearsPreviousFonts() = failedInspection(Tool.FONTS, "Helvetica")
    @Test fun failedAttachmentInspectionClearsPreviousItems() = failedInspection(Tool.ATTACHMENTS, "A.txt")
    private fun failedInspection(tool: Tool, old: String) {
        val harness = Harness(tool, invalidB = true)
        try {
            waitText(old, substring = true)
            harness.chooseB()
            val error = app.getString(if (tool == Tool.METADATA) R.string.error_read_metadata_failed else R.string.error_open_file_failed)
            waitText(error)
            compose.onAllNodesWithText(old, substring = true).assertCountEquals(0)
            compose.onNodeWithText(harness.bFile.name).assertExists()
        } finally { harness.close() }
    }
    @Test fun metadataDestinationReturnedAfterSelectionChangeIsAbandoned() {
        val harness = Harness(Tool.METADATA)
        try {
            waitText("AUTHOR_A")
            compose.onNodeWithText(app.getString(R.string.btn_wipe_and_save)).performScrollTo().performClick()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            harness.chooseB(); waitText("AUTHOR_B")
            harness.deliver()
            compose.waitUntil(10_000) { harness.abandoned() }
            compose.onNodeWithText("AUTHOR_B").assertExists()
        } finally { harness.close() }
    }
    @Test fun attachmentDestinationCannotExtractSameIdentifierFromNextDocument() {
        val harness = Harness(Tool.ATTACHMENTS)
        try {
            waitText("A.txt")
            compose.onNodeWithContentDescription(app.getString(R.string.desc_extract)).performClick()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            harness.chooseB(); waitText("B.txt")
            harness.deliver()
            compose.waitUntil(10_000) { harness.abandoned() }
            compose.onNodeWithText("B.txt").assertExists()
        } finally { harness.close() }
    }
    @Test fun fontDestinationReturnedAfterSelectionChangeIsAbandoned() {
        val harness = Harness(Tool.FONTS, embeddedFont = true)
        try {
            val export = app.getString(R.string.btn_export_embedded_fonts)
            waitText(export)
            compose.onNodeWithText(export).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            harness.chooseB(); waitText(export)
            compose.onNodeWithText(harness.bFile.name).assertExists()
            harness.deliver()
            compose.waitUntil(10_000) { harness.abandoned() }
            compose.onNodeWithText(export).assertExists()
        } finally { harness.close() }
    }
    @Test fun metadataEditPublishesCapturedFieldsAndPreservesSource() {
        val harness = Harness(Tool.METADATA)
        val original = harness.aFile.readBytes()
        try {
            waitText("AUTHOR_A")
            compose.onNodeWithText(app.getString(R.string.tab_edit_metadata)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasText("AUTHOR_A")).performScrollTo().performTextReplacement("EDITED_AUTHOR")
            compose.onNodeWithText(app.getString(R.string.btn_update_and_save)).performScrollTo().performClick()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            harness.deliver()
            val output = harness.published()
            compose.waitUntil(10_000) { harness.backs.get() == 1 }
            PDDocument.load(harness.read(output.uri)).use { assertEquals("EDITED_AUTHOR", it.documentInformation.author) }
            assertArrayEquals(original, harness.aFile.readBytes())
        } finally { harness.close() }
    }
    @Test fun fontExportPublishesNonemptyZipAndPreservesSource() {
        val harness = Harness(Tool.FONTS, embeddedFont = true)
        val original = harness.aFile.readBytes()
        try {
            val export = app.getString(R.string.btn_export_embedded_fonts)
            waitText(export)
            compose.onNodeWithText(export).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            harness.deliver()
            val output = harness.published()
            ZipInputStream(harness.read(output.uri).inputStream()).use { zip ->
                var files = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) { assertTrue(zip.readBytes().isNotEmpty()); files++ }
                    zip.closeEntry()
                }
                assertTrue(files > 0)
            }
            waitText(export)
            assertArrayEquals(original, harness.aFile.readBytes())
        } finally { harness.close() }
    }
    @Test fun attachmentExtractionPublishesSelectedPayloadAndPreservesSource() {
        val harness = Harness(Tool.ATTACHMENTS)
        val original = harness.aFile.readBytes()
        try {
            waitText("A.txt")
            compose.onNodeWithContentDescription(app.getString(R.string.desc_extract)).performClick()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            harness.deliver()
            assertEquals("PAYLOAD_A", String(harness.read(harness.published().uri)))
            assertArrayEquals(original, harness.aFile.readBytes())
        } finally { harness.close() }
    }
    @Test fun attachmentRemovalPublishesCopyAndRefreshesItsInspection() {
        val harness = Harness(Tool.ATTACHMENTS)
        val original = harness.aFile.readBytes()
        try {
            waitText("A.txt")
            compose.onNodeWithContentDescription(app.getString(R.string.btn_remove_attachment)).performClick()
            compose.onNodeWithText(app.getString(R.string.btn_remove_attachment)).performClick()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            harness.deliver()
            val output = harness.published()
            waitText(app.getString(R.string.no_attachments_found_hint))
            PDDocument.load(harness.read(output.uri)).use { assertTrue(it.documentCatalog.names?.embeddedFiles?.names.orEmpty().isEmpty()) }
            assertArrayEquals(original, harness.aFile.readBytes())
        } finally { harness.close() }
    }
    @Test fun embeddingUsesCapturedExtraFileAndPreservesExistingAttachment() {
        val harness = Harness(Tool.ATTACHMENTS)
        val original = harness.aFile.readBytes()
        try {
            waitText("A.txt")
            compose.onNodeWithText(app.getString(R.string.btn_embed_new_file)).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { harness.extraCode != null }
            harness.deliverExtra()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            harness.deliver()
            val output = harness.published()
            waitText(harness.extraFile.name)
            PDDocument.load(harness.read(output.uri)).use { document ->
                val files = document.documentCatalog.names.embeddedFiles.names
                assertEquals(2, files.size)
                assertEquals("PAYLOAD_A", files.getValue("same-key").embeddedFile.createInputStream().use { String(it.readBytes()) })
                val added = files.values.single { it.filename == harness.extraFile.name }
                assertEquals("EXTRA_PAYLOAD", added.embeddedFile.createInputStream().use { String(it.readBytes()) })
            }
            assertArrayEquals(original, harness.aFile.readBytes())
        } finally { harness.close() }
    }
    @Test fun extraPickerReturnedAfterSelectionChangeCannotStartEmbedding() {
        val harness = Harness(Tool.ATTACHMENTS)
        try {
            waitText("A.txt")
            compose.onNodeWithText(app.getString(R.string.btn_embed_new_file)).assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { harness.extraCode != null }
            harness.chooseB(); waitText("B.txt")
            harness.deliverExtra()
            compose.waitForIdle()
            assertNull(harness.pendingCode)
            assertTrue(harness.outputs().isEmpty())
            compose.onNodeWithText("B.txt").assertExists()
        } finally { harness.close() }
    }
}
