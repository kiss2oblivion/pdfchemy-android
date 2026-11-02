package com.pdfchemy.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import org.junit.Rule
import org.junit.Test

class CoreLinearizeHonestyTest {
    @get:Rule val compose = createComposeRule()
    @Test fun generationAffordanceIsDisabledAndExplainsNoOutput() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        lateinit var model: MainViewModel
        compose.runOnUiThread { model = MainViewModel(app); store.put("linearize-honesty", model) }
        try {
            compose.setContent { MaterialTheme { LinearizePdfScreen(model, onBack = {}) } }
            compose.onNodeWithText(app.getString(R.string.btn_optimize_fast_web_view)).assertIsNotEnabled()
            compose.onNodeWithText(app.getString(R.string.linearize_subtitle)).assertIsDisplayed()
            compose.onNodeWithText("PDF Linearized").assertDoesNotExist()
        } finally { compose.runOnUiThread { store.clear() } }
    }
}
