package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.RecentFilesSection
import com.pdfchemy.app.Screen
import com.pdfchemy.app.logic.HistoryRepository
import com.pdfchemy.app.logic.PdfEditor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class VanguardReaderNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cleanRecentPdfOpensReadableNativeReaderWithVanguardOnAndOff() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = app.getSharedPreferences("shrinkpdf_settings",Context.MODE_PRIVATE)
        val historyPrefs = app.getSharedPreferences("pdfchemy_history",Context.MODE_PRIVATE)
        val previousSettings = settings.all.filterKeys { it == "history_enabled" || it == "vanguard_enabled" }
        val previousHistory = historyPrefs.getString("recent_files",null)
        val source = File.createTempFile("vanguard_reader_", ".pdf", app.cacheDir)
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        val destination = AtomicReference<Screen?>()
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("vanguard-benign/01-metadata-xmp.pdf").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            settings.edit().putBoolean("history_enabled",true).commit()
            HistoryRepository(app).apply { clearHistory(); addHistoryItem(Uri.fromFile(source),source.name,"Opened") }
            compose.runOnUiThread { model=MainViewModel(app); store.put("recent",model) }
            compose.setContent { MaterialTheme { RecentFilesSection(model) { destination.set(it) } } }
            for (enabled in listOf(true,false)) {
                destination.set(null)
                compose.runOnUiThread { model.setVanguardEnabled(enabled) }
                compose.onNodeWithText(source.name).assertIsDisplayed().performClick()
                compose.waitUntil(30000) { destination.get() != null }
                val reader = destination.get() as? Screen.PdfReader
                assertNotNull("Clean PDF must enter Reader with Vanguard=$enabled",reader)
                val uri = reader!!.initialPdfUri
                assertNotNull(uri)
                assertTrue(runBlocking { PdfEditor.getPageCount(app,uri!!) } > 0)
            }
        } finally {
            compose.runOnUiThread { store.clear() }
            settings.edit().apply {
                for (key in listOf("history_enabled","vanguard_enabled")) {
                    if (previousSettings.containsKey(key)) putBoolean(key,previousSettings[key] as Boolean) else remove(key)
                }
            }.commit()
            historyPrefs.edit().apply { if (previousHistory == null) remove("recent_files") else putString("recent_files",previousHistory) }.commit()
            source.delete()
        }
    }
}
