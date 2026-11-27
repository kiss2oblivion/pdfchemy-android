package com.pdfchemy.app.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReleaseLocalizationTest {
    private fun context(tag: String): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return app.createConfigurationContext(configuration)
    }

    @Test fun defaultViewerHelpUsesRealLineBreaks() {
        val help = context("en").getString(R.string.settings_default_pdf_dialog_body)
        assertTrue("Instructions must have paragraph and step breaks", help.count { it == '\n' } >= 6)
        assertFalse("Escaped newlines must not appear to users", help.contains("\\n"))
        assertFalse("Escaped quotes must not appear to users", help.contains("\\\""))
    }

    @Test fun localizedReleaseMessagesResolveAndFormat() {
        val englishTitle = context("en").getString(R.string.settings_default_pdf_app)
        for (tag in listOf("de", "fr", "es", "it", "pt", "pt-BR", "nl", "ro", "pl", "tr", "ru", "hi", "id", "ar", "vi", "th", "ja", "ko", "zh-CN", "zh-TW")) {
            val localized = context(tag)
            assertFalse("Missing viewer translation for $tag", localized.getString(R.string.settings_default_pdf_app) == englishTitle)
            val help = localized.getString(R.string.settings_default_pdf_dialog_body)
            assertTrue("Instructions have no line breaks for $tag", help.count { it == '\n' } >= 6)
            assertFalse("Literal newline escape for $tag", help.contains("\\n"))
            val capacity = localized.getString(R.string.device_guard_desc, 123, "SIZE", "NEEDED", "AVAILABLE")
            for (value in listOf("SIZE", "NEEDED", "AVAILABLE")) {
                assertTrue("Lost capacity argument $value for $tag", capacity.contains(value))
            }
            assertFalse("Unformatted capacity message for $tag", capacity.contains("%"))
            val page = localized.getString(R.string.editor_rendering_page, 123)
            assertFalse("Unformatted page number for $tag", page.contains("%"))
        }
    }
    @Test fun romanianRecoveryMessagesAreActuallyTranslated() {
        val en = context("en")
        val ro = context("ro")
        for (id in listOf(R.string.document_load_recovery, R.string.document_damaged_recovery,
            R.string.error_operation_recovery, R.string.error_storage_recovery,
            R.string.error_password_recovery, R.string.reader_reset_defaults)) {
            assertFalse("Romanian resource ${ro.resources.getResourceEntryName(id)} is still English",
                ro.getString(id) == en.getString(id))
        }
    }
}
