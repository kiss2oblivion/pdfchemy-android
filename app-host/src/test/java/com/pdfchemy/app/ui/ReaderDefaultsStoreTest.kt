package com.pdfchemy.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderDefaultsStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("reader_defaults_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun defaultsSurviveStoreRecreationAndResetPreservesOtherSettings() {
        prefs.edit().putBoolean("history_enabled", true).putInt("reader_scroll_index_document", 42).commit()
        ReaderDefaultsStore(prefs).save(ReaderDefaults("SEPIA", 24f, true))
        val reopened = ReaderDefaultsStore(prefs)
        assertEquals(ReaderDefaults("SEPIA", 24f, true), reopened.load())
        reopened.reset()
        assertEquals(ReaderDefaults(), reopened.load())
        assertTrue(prefs.getBoolean("history_enabled", false))
        assertEquals(42, prefs.getInt("reader_scroll_index_document", 0))
    }

    @Test fun invalidStoredChoicesRecoverToUsableDefaults() {
        prefs.edit().putString("reader_default_theme", "missing").putFloat("reader_default_font_size", Float.NaN).commit()
        assertEquals(ReaderDefaults(), ReaderDefaultsStore(prefs).load())
        ReaderDefaultsStore(prefs).save(ReaderDefaults("DARK", 100f, false))
        assertEquals(ReaderDefaults("DARK", 32f, false), ReaderDefaultsStore(prefs).load())
    }
}
