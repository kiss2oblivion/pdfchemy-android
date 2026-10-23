package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.MainApp
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises the actual Reader and navigation, rather than just the recent-file destination. */
class ReaderDocumentLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readerEditorRoundTripPreservesStagedDocumentWithVanguardEnabled() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        val previous = prefs.all["vanguard_enabled"]
        val store = ViewModelStore()
        val staged = InstrumentationRegistry.getInstrumentation().context.assets
            .open("vanguard-benign/01-metadata-xmp.pdf").use { DocumentStager.stageStream(app, it, "pdf") }
        val file = File(staged.uri.path!!)
        lateinit var model: MainViewModel
        val showApp = mutableStateOf(true)
        try {
            compose.runOnUiThread {
                model = MainViewModel(app)
                store.put("reader-lifecycle", model)
                model.setVanguardEnabled(true)
            }
            val incoming = MutableStateFlow<Uri?>(staged.uri)
            compose.setContent { MaterialTheme {
                if (showApp.value) MainApp(viewModel = model, incomingPdfUriState = incoming, isScreenshotRun = true)
            } }
            waitForReader()
            compose.onNodeWithContentDescription("Edit").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("Page 1 of 1").fetchSemanticsNodes().size == 1
            }
            compose.waitForIdle()
            assertTrue("Reader exit must retain the snapshot for Editor and its return route", file.exists())
            compose.onNodeWithContentDescription("Back").performClick()
            waitForReader()
            assertTrue("Returning Reader must own the original staged snapshot", file.exists())
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("PDFchemy Tools").fetchSemanticsNodes().size == 1
            }
            compose.waitForIdle()
            assertFalse("Leaving Reader must release its snapshot", file.exists())
        } finally {
            compose.runOnIdle { showApp.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            prefs.edit().apply {
                if (previous is Boolean) putBoolean("vanguard_enabled", previous) else remove("vanguard_enabled")
            }.commit()
            DocumentStager.release(staged)
        }
    }

    private fun waitForReader() {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("1 of 1").fetchSemanticsNodes().size == 1 ||
                compose.onAllNodesWithText("Document unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Document unavailable").assertDoesNotExist()
        compose.onNodeWithText("1 of 1").assertIsDisplayed()
        // Page count precedes the asynchronous native bitmap/layout handoff.
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithContentDescription("Page 1").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithContentDescription("Page 1").assertIsDisplayed()
    }
}
