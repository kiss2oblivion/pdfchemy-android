package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.R
import com.pdfchemy.app.Screen
import com.pdfchemy.app.readerAwareScreenSaver
import com.pdfchemy.app.utils.DocumentStager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderSavedSnapshotRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restorationUsesTheLiveSnapshotAfterTheRequestedCopyIsRetired() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        val previous = prefs.all["vanguard_enabled"]
        val requested = File.createTempFile("reader-restored-request-", ".pdf", app.cacheDir)
        val original = InstrumentationRegistry.getInstrumentation().context.assets
            .open("vanguard-benign/01-metadata-xmp.pdf").use { it.readBytes() }
        requested.writeBytes(original)
        val requestedUri = Uri.fromFile(requested)
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        var liveUri: Uri? = null
        val visible = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        fun waitForReader() {
            val inaccessible = app.getString(R.string.vanguard_provider_error_title)
            compose.waitUntil(30_000) {
                runCatching { compose.onNodeWithContentDescription("Page 1").assertIsDisplayed() }.isSuccess ||
                    compose.onAllNodesWithText(inaccessible).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(inaccessible).assertDoesNotExist()
            compose.onNodeWithContentDescription("Page 1").assertIsDisplayed()
            compose.onNodeWithContentDescription(app.getString(R.string.reader_action_search)).assertIsDisplayed()
        }
        try {
            compose.runOnUiThread {
                model = MainViewModel(app).also { store.put("restaged-reader-route", it) }
                model.setVanguardEnabled(true)
            }
            restoration.setContent { MaterialTheme {
                if (visible.value) {
                    // Use the production navigation saver and actual Reader. Enter
                    // directly with the recovered request, as a restored route does;
                    // a new incoming intent would pre-stage it in MainApp first.
                    val saver = remember(model) { readerAwareScreenSaver(model) }
                    val route by rememberSaveable(stateSaver = saver) {
                        mutableStateOf<Screen>(Screen.PdfReader(requestedUri))
                    }
                    PdfReaderScreen(model, (route as Screen.PdfReader).initialPdfUri, {}, {})
                }
            } }
            waitForReader()
            compose.runOnIdle { liveUri = model.readerSourceUri }
            assertNotEquals(requestedUri, liveUri)
            assertEquals(requestedUri, DocumentStager.originalSourceFor(requireNotNull(liveUri)))
            assertTrue("Retire only this test's obsolete request", requested.delete())
            restoration.emulateSavedInstanceStateRestore()
            waitForReader()
            assertArrayEquals("Restoration must preserve the owned snapshot", original, File(liveUri!!.path!!).readBytes())
        } finally {
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            liveUri?.let(DocumentStager::release)
            requested.delete()
            prefs.edit().apply {
                if (previous is Boolean) putBoolean("vanguard_enabled", previous) else remove("vanguard_enabled")
            }.commit()
        }
    }
}
