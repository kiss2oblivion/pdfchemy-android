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
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Real Vanguard selection -> staged URI -> text screen -> fresh SAF copy -> isolated export. */
class TextExportNamingIntegrityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stagedSelectionDisplaysOriginalName() = exercise(export = false)
    @Test fun stagedExportUsesOriginalNameAndPreservesExistingCopy() = exercise(export = true)

    private fun exercise(export: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)
        val resolver = app.contentResolver
        val control = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        val tree = DocumentsContract.buildTreeDocumentUri(control.authority!!, "root")
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        resolver.call(control, "configure", null, null)
        val settings = app.getSharedPreferences("shrinkpdf_settings", Application.MODE_PRIVATE)
        val oldFolder = settings.getString(OutputPolicy.DIRECTORY_KEY, null)
        val oldVanguard = settings.getBoolean("vanguard_enabled", true)
        settings.edit().remove(OutputPolicy.DIRECTORY_KEY).putBoolean("vanguard_enabled", true).commit()
        val name = "Quarterly Report.pdf"
        val text = "PUBLIC TEXT EXPORT FIXTURE"
        val sourceBytes = ByteArrayOutputStream().use { output ->
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(300f, 200f)); document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(20f, 100f); stream.showText(text); stream.endText()
                }
                document.save(output)
            }
            output.toByteArray()
        }
        fun create(displayName: String, type: String, bytes: ByteArray): Uri {
            val uri = requireNotNull(DocumentsContract.createDocument(resolver, root, type, displayName))
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            return uri
        }
        fun bytes(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        val source = create(name, "application/pdf", sourceBytes)
        val oldBytes = "previous recipient text remains unchanged".toByteArray()
        val old = create("Quarterly Report_text.txt", "text/plain", oldBytes)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                val uri = when (contract) {
                    is ActivityResultContracts.OpenDocument -> source
                    is ActivityResultContracts.OpenDocumentTree -> tree
                    else -> error("Unexpected contract: $contract")
                }
                dispatchResult(code, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        lateinit var model: MainViewModel
        compose.runOnUiThread { model = MainViewModel(app); store.put("text-export-naming", model) }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme { if (visible.value) ExtractTextScreen(model, onBack = {}) }
                }
            }
            compose.onNodeWithText(app.getString(R.string.select_pdf)).performClick()
            val action = app.getString(R.string.extract_save_as_txt)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(action).fetchSemanticsNodes().size == 1 }
            if (!export) {
                compose.onNodeWithText(name).assertExists()
            } else {
                compose.onNodeWithText(action).performClick()
                compose.waitUntil(30_000) {
                    model.uiState.value is MainViewModel.UiState.Success || model.uiState.value is MainViewModel.UiState.Error
                }
                val result = model.uiState.value
                assertTrue("Real isolated text export must finish successfully: $result", result is MainViewModel.UiState.Success)
                val uri = (result as MainViewModel.UiState.Success).outputUris.single()
                assertTrue(bytes(uri).toString(Charsets.UTF_8).contains(text))
                assertFalse(OutputPolicy.isFreshCopy(uri))
                assertArrayEquals(sourceBytes, bytes(source))
                assertArrayEquals(oldBytes, bytes(old))
                assertEquals("Quarterly Report_text (2).txt", DocumentFile.fromSingleUri(app, uri)!!.name)
            }
            assertArrayEquals(sourceBytes, bytes(source))
            assertArrayEquals(oldBytes, bytes(old))
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            DocumentFile.fromTreeUri(app, tree)!!.listFiles().filter { DocumentsContract.getDocumentId(it.uri) != "existing" }.forEach {
                OutputPolicy.unregisterFreshCopy(it.uri); it.delete()
            }
            settings.edit().putBoolean("vanguard_enabled", oldVanguard).apply {
                if (oldFolder == null) remove(OutputPolicy.DIRECTORY_KEY) else putString(OutputPolicy.DIRECTORY_KEY, oldFolder)
            }.commit()
        }
    }
}
