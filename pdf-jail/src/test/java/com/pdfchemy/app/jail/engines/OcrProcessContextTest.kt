package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OcrProcessContextTest {
    @Test fun sdkPreferencesRemainEphemeralAcrossProcessContexts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val process = OcrProcessContext(context)
        assertSame(process, process.applicationContext)
        val prefs = process.getSharedPreferences("mlkit_regression", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("sdkId", "session only").putLong("version", 2L).commit())
        assertEquals("session only", process.getSharedPreferences("mlkit_regression", 0).getString("sdkId", null))
        assertEquals(2L, prefs.getLong("version", 0L))
        assertFalse(context.getSharedPreferences("mlkit_regression", 0).contains("sdkId"))
        assertFalse(OcrProcessContext(context).getSharedPreferences("mlkit_regression", 0).contains("sdkId"))
    }
    @Test fun editorsUseDefensiveCopiesAndRemoveAndClearValues() {
        val prefs = EphemeralPreferences()
        val initial = mutableSetOf("one")
        prefs.edit().putStringSet("names", initial).putBoolean("flag", true).apply()
        initial.add("two")
        prefs.getStringSet("names", null)!!.add("three")
        assertEquals(setOf("one"), prefs.getStringSet("names", null))
        prefs.edit().remove("flag").commit()
        assertFalse(prefs.contains("flag"))
        prefs.edit().clear().putInt("new", 4).commit()
        assertEquals(mapOf("new" to 4), prefs.all)
    }
}
