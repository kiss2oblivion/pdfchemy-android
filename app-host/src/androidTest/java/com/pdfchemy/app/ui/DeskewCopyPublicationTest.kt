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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual Compose callback -> fresh SAF copy -> isolated worker -> publication. */
class DeskewCopyPublicationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun zeroCorrectionsPublishFreshCopyAndKeepExistingDocument() = exercise(false)
    @Test fun engineFailureDeletesFreshCopyWithoutDisplayingSuccess() = exercise(true)

    private fun exercise(malformed: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)
        val control = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        val tree = DocumentsContract.buildTreeDocumentUri(control.authority!!, "root")
        app.contentResolver.call(control, "configure", null, null)
        val existing = DocumentsContract.buildDocumentUriUsingTree(tree, "existing")
        fun bytes(uri: Uri) = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val old = bytes(existing)
        val input = File.createTempFile("deskew_screen_", ".pdf", app.cacheDir)
        if (malformed) input.writeText("%PDF-1.4\nmalformed private source")
        else PDDocument().use { doc -> doc.addPage(PDPage()); doc.save(input) }
        val source = input.readBytes()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue("Deskew chooses a folder, never an existing output document", contract is ActivityResultContracts.OpenDocumentTree)
                dispatchResult(code, Activity.RESULT_OK, Intent().setData(tree))
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        lateinit var model: MainViewModel
        compose.runOnUiThread { model = MainViewModel(app); store.put("deskew-copy", model) }
        fun state() = app.contentResolver.call(control, "status", null, null)!!
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    MaterialTheme { if (visible.value) DeskewScreen(model, Uri.fromFile(input), onBack = {}) }
                }
            }
            val action = app.getString(R.string.deskew_action_straighten)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(action).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(action).performScrollTo().performClick()
            if (malformed) {
                compose.waitUntil(30_000) { state().getInt("createdCount") == 1 && state().getInt("deletedCount") == 1 }
                assertNull(state().getString("firstId"))
                compose.onAllNodesWithText(app.getString(R.string.deskew_no_tilt)).assertCountEquals(1)
                val failed = DocumentsContract.buildDocumentUriUsingTree(tree, "doc_1")
                assertFalse(DocumentFile.fromSingleUri(app, failed)!!.exists())
                assertFalse(OutputPolicy.isFreshCopy(failed))
            } else {
                compose.waitUntil(30_000) {
                    val id = state().getString("firstId")
                    id != null && !OutputPolicy.isFreshCopy(DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
                val output = DocumentsContract.buildDocumentUriUsingTree(tree, state().getString("firstId")!!)
                PDDocument.load(bytes(output)).use { assertEquals(1, it.numberOfPages) }
                compose.onAllNodesWithText(app.getString(R.string.deskew_no_tilt)).assertCountEquals(2)
            }
            assertArrayEquals(old, bytes(existing))
            assertArrayEquals(source, input.readBytes())
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            DocumentFile.fromTreeUri(app, tree)!!.listFiles().filter { it.uri != existing }.forEach {
                OutputPolicy.unregisterFreshCopy(it.uri); it.delete()
            }
            input.delete()
        }
    }
}
