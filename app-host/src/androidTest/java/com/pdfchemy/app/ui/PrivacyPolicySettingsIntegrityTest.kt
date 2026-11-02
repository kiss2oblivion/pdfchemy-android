package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.SettingsScreen
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PrivacyPolicySettingsIntegrityTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val model = MainViewModel(app).also { store.put("privacy-settings", it) }
    private val preferences = app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private var hadConsentKey = false
    private var originalConsent = false

    @Before fun saveConsent() {
        hadConsentKey = preferences.contains("has_consented")
        originalConsent = preferences.getBoolean("has_consented", false)
    }

    @After fun restoreConsent() {
        store.clear()
        val edit = preferences.edit()
        if (hadConsentKey) edit.putBoolean("has_consented", originalConsent)
        else edit.remove("has_consented")
        assertTrue(edit.commit())
    }

    private fun showSettings() {
        compose.setContent { MaterialTheme { SettingsScreen(model, onBack = {}) } }
    }

    private fun openPolicy() {
        compose.onNodeWithText(app.getString(R.string.settings_privacy_policy))
            .performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
    }

    private fun closePolicy() {
        compose.onNode(hasText(app.getString(R.string.ok)) and hasClickAction()
            and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun actualSettingsPolicyExposesContactRetentionAndSdkPracticesOffline() {
        showSettings()
        openPolicy()

        compose.onNode(hasText(app.getString(R.string.settings_privacy_policy))
            and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("Shared copies and deletion").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("64-copy / 250 MiB", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Advertising and privacy choices")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("IP addresses", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("For questions about PDFchemy", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("For questions about PDFchemy", substring = true)
            .assertTextContains("cucosandreiioan@gmail.com", substring = true)
        closePolicy()
    }

    @Test fun viewingAndClosingPolicyNeverChangesRecordedConsent() {
        assertTrue(preferences.edit().putBoolean("has_consented", false).commit())
        showSettings()

        openPolicy()
        assertFalse(preferences.getBoolean("has_consented", true))
        closePolicy()
        assertFalse(preferences.getBoolean("has_consented", true))

        assertTrue(preferences.edit().putBoolean("has_consented", true).commit())
        openPolicy()
        assertTrue(preferences.getBoolean("has_consented", false))
        closePolicy()
        assertTrue(preferences.getBoolean("has_consented", false))
    }

    @Test fun bundledPolicyRemainsScrollableAtNarrowWidthAndDoubleFontScale() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(280.dp).height(320.dp)) {
                        ReleasePrivacyPolicyContent()
                    }
                }
            }
        }

        compose.onNodeWithText("Privacy Policy — PDFchemy Android").assertIsDisplayed()
        compose.onNodeWithText("Security and retention limits").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Public Android source", substring = true)
            .performScrollTo().assertIsDisplayed()
    }
}
