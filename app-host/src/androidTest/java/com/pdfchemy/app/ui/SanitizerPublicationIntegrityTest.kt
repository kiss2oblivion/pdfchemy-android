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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Actual screen options and completion state, backed by the real isolated sanitizer and SAF provider. */
class SanitizerPublicationIntegrityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun deselectedAttachmentPurgePreservesEmbeddedPayload() = exercise(keepAttachment = true)
    @Test fun selectedAttachmentPurgeRemovesEmbeddedPayload() = exercise(keepAttachment = false)
    @Test fun malformedInputNeverDisplaysSuccessOrLeavesOutput() = exercise(keepAttachment = false, malformed = true)

    private fun exercise(keepAttachment: Boolean, malformed: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)
        val resolver = app.contentResolver
        val control = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        val tree = DocumentsContract.buildTreeDocumentUri(control.authority!!, "root")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        resolver.call(control, "configure", null, null)
        resolver.call(control, "release", null, null)
        val settings = app.getSharedPreferences("shrinkpdf_settings", Application.MODE_PRIVATE)
        val oldFolder = settings.getString(OutputPolicy.DIRECTORY_KEY, null)
        settings.edit().remove(OutputPolicy.DIRECTORY_KEY).commit()
        val payload = "PRIVATE ATTACHMENT CONTENT".toByteArray()
        val input = if (malformed) "%PDF-invalid".toByteArray() else ByteArrayOutputStream().use { output ->
            PDDocument().use { document ->
                document.addPage(PDPage())
                document.documentInformation.author = "PRIVATE AUTHOR"
                val spec = PDComplexFileSpecification().apply {
                    file = "private.txt"
                    embeddedFile = PDEmbeddedFile(document, ByteArrayInputStream(payload)).apply { subtype = "text/plain" }
                }
                document.documentCatalog.names = PDDocumentNameDictionary(document.documentCatalog).apply {
                    embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("private" to spec) }
                }
                document.save(output)
            }
            output.toByteArray()
        }
        fun create(name: String, bytes: ByteArray): Uri {
            val uri = requireNotNull(DocumentsContract.createDocument(resolver, root, "application/pdf", name))
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            return uri
        }
        fun bytes(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        val source = create("Sanitizer.pdf", input)
        val oldBytes = "existing target must survive".toByteArray()
        val old = create("Sanitizer_sanitized.pdf", oldBytes)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val uri = when (contract) {
                    is ActivityResultContracts.OpenDocument -> source
                    is ActivityResultContracts.OpenDocumentTree -> tree
                    else -> error("Unexpected public picker: $contract")
                }
                dispatchResult(code, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        lateinit var model: MainViewModel
        compose.runOnUiThread { model = MainViewModel(app); store.put("sanitizer-integrity", model) }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme { if (visible.value) DocumentSanitizerScreen(model, onBack = {}) }
                }
            }
            compose.onNodeWithText(app.getString(R.string.select_pdf)).performClick()
            val action = app.getString(R.string.sanitizer_action_sanitize)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(action).fetchSemanticsNodes().size == 1 }
            if (keepAttachment) {
                compose.onNode(isToggleable() and hasAnySibling(hasText("Purge Hidden Embedded Files")))
                    .performScrollTo().performClick().assertIsOff()
            }
            compose.onNodeWithText(action).performScrollTo().performClick()
            val message = app.getString(if (malformed) R.string.integrity_sanitizer_failed else R.string.sanitizer_success)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            val outputs = DocumentFile.fromTreeUri(app, tree)!!.listFiles()
                .filter { it.uri != source && it.uri != old && DocumentsContract.getDocumentId(it.uri) != "existing" }
            if (malformed) {
                compose.onNodeWithText(app.getString(R.string.sanitizer_success)).assertDoesNotExist()
                assertTrue("Failed sanitizer must remove its fresh incomplete copy", outputs.isEmpty())
            } else {
                val output = outputs.single()
                assertEquals("Sanitizer_sanitized (2).pdf", output.name)
                assertFalse(OutputPolicy.isFreshCopy(output.uri))
                PDDocument.load(ByteArrayInputStream(bytes(output.uri))).use { document ->
                    assertEquals(1, document.numberOfPages)
                    assertNull(document.documentInformation.author)
                    assertNull(document.documentCatalog.metadata)
                    val files = document.documentCatalog.names?.embeddedFiles?.names.orEmpty()
                    if (keepAttachment) {
                        assertArrayEquals(payload, files.getValue("private").embeddedFile.createInputStream().use { it.readBytes() })
                    } else assertTrue(files.isEmpty())
                }
                compose.onNodeWithText(app.getString(R.string.integrity_sanitizer_selected_complete)).assertExists()
            }
            assertArrayEquals(input, bytes(source))
            assertArrayEquals(oldBytes, bytes(old))
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            DocumentFile.fromTreeUri(app, tree)!!.listFiles().filter { DocumentsContract.getDocumentId(it.uri) != "existing" }.forEach {
                OutputPolicy.unregisterFreshCopy(it.uri); it.delete()
            }
            settings.edit().apply {
                if (oldFolder == null) remove(OutputPolicy.DIRECTORY_KEY) else putString(OutputPolicy.DIRECTORY_KEY, oldFolder)
            }.commit()
        }
    }
}
