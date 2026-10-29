package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.MainApp
import com.pdfchemy.app.R
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream

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
            compose.onNodeWithContentDescription(app.getString(R.string.menu_edit_pdf)).performClick()
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

    @Test fun savedToolRouteReturnsToReaderAfterCompositionAndViewModelReplacement() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        val previous = prefs.all["vanguard_enabled"]
        val staged = InstrumentationRegistry.getInstrumentation().context.assets
            .open("vanguard-benign/01-metadata-xmp.pdf").use { DocumentStager.stageStreamWithLease(app, it, "pdf") }
        val source = File(staged.document.uri.path!!)
        val original = source.readBytes()
        val incoming = MutableStateFlow<Uri?>(staged.document.uri)
        val showApp = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        try {
            prefs.edit().putBoolean("vanguard_enabled", true).commit()
            restoration.setContent {
                if (showApp.value) {
                    val store = remember { ViewModelStore() }
                    val model = remember { MainViewModel(app).also { store.put("restored-reader", it) } }
                    DisposableEffect(store) { onDispose { store.clear() } }
                    MaterialTheme { MainApp(viewModel = model, incomingPdfUriState = incoming, isScreenshotRun = true) }
                }
            }
            waitForReader()
            compose.onNodeWithContentDescription(app.getString(R.string.menu_edit_pdf)).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Page 1 of 1").fetchSemanticsNodes().size == 1 }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Page 1 of 1").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithContentDescription("Back").performClick()
            waitForReader()
            assertArrayEquals("Restoration must preserve the input snapshot", original, source.readBytes())
        } finally {
            compose.runOnIdle { showApp.value = false }
            compose.waitForIdle()
            staged.close(); DocumentStager.release(staged.document)
            prefs.edit().apply {
                if (previous is Boolean) putBoolean("vanguard_enabled", previous) else remove("vanguard_enabled")
            }.commit()
        }
    }

    @Test fun survivingViewModelRetainsReaderSnapshotAcrossCompositionRecreation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        val previous = prefs.all["vanguard_enabled"]
        val store = ViewModelStore()
        val staged = InstrumentationRegistry.getInstrumentation().context.assets
            .open("vanguard-benign/01-metadata-xmp.pdf").use { DocumentStager.stageStream(app, it, "pdf") }
        val file = File(staged.uri.path!!)
        val original = file.readBytes()
        lateinit var model: MainViewModel
        val incoming = MutableStateFlow<Uri?>(staged.uri)
        val showApp = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        try {
            compose.runOnUiThread {
                model = MainViewModel(app)
                store.put("reader-recreation", model)
                model.setVanguardEnabled(true)
            }
            restoration.setContent { MaterialTheme {
                if (showApp.value) MainApp(viewModel = model, incomingPdfUriState = incoming, isScreenshotRun = true)
            } }
            waitForReader()
            restoration.emulateSavedInstanceStateRestore()
            assertTrue("A surviving ViewModel must retain the Reader snapshot across UI recreation", file.exists())
            waitForReader()
            assertArrayEquals(original, file.readBytes())
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

    @Test fun nonzeroSessionPositionRestoresAfterTheListBecomesVisible() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        val previous = prefs.all.filterKeys { it in setOf("vanguard_enabled", "remember_position") }
        PDFBoxResourceLoader.init(app)
        val bytes = ByteArrayOutputStream().use { output ->
            PDDocument().use { document ->
                repeat(4) { pageIndex ->
                    val page = PDPage(PDRectangle(300f, 400f)).also(document::addPage)
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 18f)
                        stream.newLineAtOffset(40f, 250f); stream.showText("Reader page ${pageIndex + 1}"); stream.endText()
                    }
                }
                document.save(output)
            }
            output.toByteArray()
        }
        val staged = DocumentStager.stageStream(app, bytes.inputStream(), "pdf")
        val source = File(staged.uri.path!!)
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        val visible = mutableStateOf(true)
        val incoming = MutableStateFlow<Uri?>(staged.uri)
        try {
            compose.runOnUiThread {
                model = MainViewModel(app).also { store.put("reader-position-layout", it) }
                model.setVanguardEnabled(true); model.setRememberPositionEnabled(true)
                model.readerPositions[staged.uri.toString()] = MainViewModel.ReaderPosition(2, 73)
            }
            compose.setContent { MaterialTheme {
                if (visible.value) MainApp(viewModel = model, incomingPdfUriState = incoming, isScreenshotRun = true)
            } }
            val indicator = app.getString(R.string.reader_page_indicator, 3, 4)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(indicator).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(indicator).assertIsDisplayed()
            compose.waitUntil(30_000) {
                runCatching { compose.onNodeWithContentDescription("Page 3").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithContentDescription("Page 3").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(MainViewModel.ReaderPosition(2, 73), model.readerPositions[staged.uri.toString()])
            }
            assertArrayEquals("Restoring position must not mutate the PDF", bytes, source.readBytes())
        } finally {
            compose.runOnIdle { visible.value = false }; compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            prefs.edit().apply {
                for (key in setOf("vanguard_enabled", "remember_position")) {
                    if (previous[key] is Boolean) putBoolean(key, previous[key] as Boolean) else remove(key)
                }
            }.commit()
            DocumentStager.release(staged)
        }
    }

    private fun waitForReader() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val indicator = app.getString(R.string.reader_page_indicator, 1, 1)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(indicator).fetchSemanticsNodes().size == 1 ||
                compose.onAllNodesWithText("Document unavailable").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Document unavailable").assertDoesNotExist()
        compose.onNodeWithText(indicator).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.reader_action_search)).assertIsDisplayed()
        // Page count precedes the asynchronous native bitmap/layout handoff.
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithContentDescription("Page 1").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithContentDescription("Page 1").assertIsDisplayed()
    }
}
