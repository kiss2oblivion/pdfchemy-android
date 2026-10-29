package com.pdfchemy.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import org.junit.Rule
import org.junit.Test
import java.io.File

class CorePdfAHonestyTest {
    @get:Rule val compose = createComposeRule()
    @Test fun actualInspectionDisplaysNotCertifiedAndNoCompliancePercentage() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)
        val input = File.createTempFile("pdfa_ui_", ".pdf", app.cacheDir)
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.documentCatalog.metadata = PDMetadata(doc).apply { importXMPMetadata("<fake pdfaid:part=\"1\"/>".toByteArray()) }
            doc.save(input)
        }
        val store = ViewModelStore()
        val visible = mutableStateOf(true)
        lateinit var model: MainViewModel
        compose.runOnUiThread { model = MainViewModel(app); store.put("pdfa-honesty", model) }
        try {
            compose.setContent { MaterialTheme {
                if (visible.value) PdfAValidatorScreen(model, Uri.fromFile(input), onBack = {})
            } }
            compose.waitUntil(30_000) { compose.onAllNodesWithText("NOT_CERTIFIED").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("NOT_CERTIFIED").assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.pdfa_subtitle)).assertIsDisplayed()
            compose.onNodeWithText("Archival Ready", substring = true).assertDoesNotExist()
            compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            input.delete()
        }
    }
}
