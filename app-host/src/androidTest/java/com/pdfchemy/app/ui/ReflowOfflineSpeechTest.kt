package com.pdfchemy.app.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.pdfchemy.app.R
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ReflowOfflineSpeechTest {
    // IO completion must queue composition on the rule's clock, never recompose inline on an IO thread.
    @get:Rule val compose = createComposeRule(effectContext = StandardTestDispatcher())
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val model = MainViewModel(app).also { store.put("offline-speech", it) }
    private val control = Uri.parse("content://com.pdfchemy.app.test.speech")
    private var source: File? = null
    private val engines = mutableListOf<TextToSpeech>()
    private fun status() = app.contentResolver.call(control, "state", null, null)!!

    @After fun cleanup() {
        compose.runOnIdle { store.clear(); engines.forEach { it.shutdown() } }
        source?.delete()
    }

    private fun showReader(networkOnly: Boolean) {
        app.contentResolver.call(control, "configure", null, Bundle().apply {
            putBoolean("networkOnly", networkOnly)
            putString("locale", Locale.getDefault().toLanguageTag())
        })
        PDFBoxResourceLoader.init(app)
        val documentFile = File.createTempFile("offline_speech_", ".pdf", app.cacheDir).also { source = it }
        PDDocument().use { document ->
            val page = PDPage().also(document::addPage)
            PDPageContentStream(document, page).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                it.newLineAtOffset(30f, 700f); it.showText("Synthetic offline speech phrase."); it.endText()
            }
            document.save(documentFile)
        }
        val initialized = AtomicReference<Int?>()
        compose.setContent {
            MaterialTheme {
                ReflowReaderScreen(Uri.fromFile(documentFile), model, onBack = {}, speechFactory = { context, listener ->
                    TextToSpeech(context, { result -> listener.onInit(result); initialized.set(result) }, "com.pdfchemy.app.test")
                        .also(engines::add)
                })
            }
        }
        compose.waitUntil(60000) { initialized.get() != null }
        assertEquals(TextToSpeech.SUCCESS, initialized.get())
        val engine = engines.single()
        assertTrue("Fixture engine must be discoverable", engine.engines.any { it.name == "com.pdfchemy.app.test" })
        assertEquals("Do not accept framework fallback to another engine",
            if (networkOnly) setOf("network") else setOf("network", "offline"), engine.voices.map { it.name }.toSet())
        assertTrue("Fixture must receive the voice discovery call", status().getInt("queries") > 0)
        compose.waitUntil(60000) {
            compose.onAllNodesWithText("Synthetic offline speech phrase.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Read Aloud").performClick()
    }

    @Test fun networkOnlyEngineReceivesNoDocumentText() {
        showReader(networkOnly = true)
        compose.waitUntil(15000) {
            status().getStringArrayList("texts")!!.isNotEmpty() || compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("No paragraph may reach a network voice", 0, status().getStringArrayList("texts")!!.size)
        assertTrue("The offline policy must query the fixture's voices", status().getInt("queries") > 0)
        compose.onNode(isDialog()).assertExists()
    }

    @Test fun readerOverridesNetworkDefaultWithInstalledOfflineVoice() {
        showReader(networkOnly = false)
        compose.waitUntil(15000) { status().getStringArrayList("texts")!!.isNotEmpty() }
        assertTrue(status().getStringArrayList("voices")!!.all { it == "offline" })
        assertTrue(status().getStringArrayList("texts")!!.any { it.contains("Synthetic offline speech phrase.") })
    }

    @Test fun retryRechecksInstalledVoicesAndWaitsForAnExplicitPlay() {
        showReader(networkOnly = true)
        compose.waitUntil(15000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(status().getStringArrayList("texts")!!.isEmpty())
        compose.onNodeWithText(app.getString(R.string.settings_title)).assertExists()
        app.contentResolver.call(control, "configure", null, Bundle().apply {
            putBoolean("networkOnly", false)
            putString("locale", Locale.getDefault().toLanguageTag())
        })
        compose.onNodeWithText(app.getString(R.string.retry)).performClick()
        compose.waitUntil(15000) { engines.size == 2 && status().getInt("queries") > 0 }
        compose.waitForIdle()
        assertTrue("Retry must not submit a document automatically", status().getStringArrayList("texts")!!.isEmpty())
        compose.onNodeWithContentDescription("Play/Pause").performClick()
        compose.waitUntil(15000) { status().getStringArrayList("texts")!!.isNotEmpty() }
        assertTrue(status().getStringArrayList("voices")!!.all { it == "offline" })
    }
}
