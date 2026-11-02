package com.pdfchemy.app.ui

import android.content.SharedPreferences

data class ReaderDefaults(val theme: String = "LIGHT", val fontSize: Float = 16f, val serif: Boolean = false)

/** Explicit local preferences; reset never touches document positions or privacy settings. */
class ReaderDefaultsStore(private val prefs: SharedPreferences) {
    private fun normalized(value: ReaderDefaults) = value.copy(
        theme = value.theme.takeIf { it in setOf("LIGHT", "SEPIA", "DARK", "OLED") } ?: "LIGHT",
        fontSize = if (value.fontSize.isFinite()) value.fontSize.coerceIn(12f, 32f) else 16f
    )

    fun load(): ReaderDefaults = normalized(ReaderDefaults(
        theme = runCatching { prefs.getString("reader_default_theme", "LIGHT") }.getOrNull() ?: "LIGHT",
        fontSize = runCatching { prefs.getFloat("reader_default_font_size", 16f) }.getOrDefault(16f),
        serif = runCatching { prefs.getBoolean("reader_default_serif", false) }.getOrDefault(false)
    ))

    fun save(value: ReaderDefaults) {
        val safe = normalized(value)
        prefs.edit().putString("reader_default_theme", safe.theme)
            .putFloat("reader_default_font_size", safe.fontSize)
            .putBoolean("reader_default_serif", safe.serif).apply()
    }

    fun reset() {
        prefs.edit().remove("reader_default_theme").remove("reader_default_font_size")
            .remove("reader_default_serif").apply()
    }
}
