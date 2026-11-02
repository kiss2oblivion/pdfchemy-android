package com.pdfchemy.app.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompressionDefaultsStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("compression_defaults_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun lateRecommendationsOnlyChangeUntouchedSettings() {
        val choices = CompressionDefaultsStore(prefs)
        choices.beginInput()
        choices.userChanged(CompressionChoice.QUALITY)
        choices.userChanged(CompressionChoice.LOSSLESS)
        assertFalse(choices.mayRecommend(CompressionChoice.QUALITY))
        assertFalse(choices.mayRecommend(CompressionChoice.LOSSLESS))
        assertTrue(choices.mayRecommend(CompressionChoice.GRAYSCALE))
        // Merely changing settings does not silently save a recurring profile.
        assertEquals(CompressionDefaults(), CompressionDefaultsStore(prefs).load())
    }

    @Test fun explicitProfileSurvivesNewInputsIncludingFalseChoicesAndScopedReset() {
        prefs.edit().putBoolean("history_enabled", true).commit()
        val saved = CompressionDefaults(0.75f, false, true, true)
        CompressionDefaultsStore(prefs).save(saved)
        val reopened = CompressionDefaultsStore(prefs)
        reopened.beginInput()
        assertEquals(saved, reopened.load())
        CompressionChoice.entries.forEach { assertFalse(reopened.mayRecommend(it)) }
        reopened.reset()
        assertEquals(CompressionDefaults(), reopened.load())
        CompressionChoice.entries.forEach { assertTrue(reopened.mayRecommend(it)) }
        assertTrue(prefs.getBoolean("history_enabled", false))
    }
}
