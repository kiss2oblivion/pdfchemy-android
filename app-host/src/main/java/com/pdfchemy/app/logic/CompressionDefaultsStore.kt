package com.pdfchemy.app.logic

import android.content.SharedPreferences

enum class CompressionChoice { QUALITY, GRAYSCALE, LOSSLESS, STRIP_METADATA }
data class CompressionDefaults(val quality: Float = 0.5f, val grayscale: Boolean = false,
    val lossless: Boolean = false, val stripMetadata: Boolean = false)

/** Saved defaults are explicit; per-document choices only guard late recommendations. */
class CompressionDefaultsStore(private val prefs: SharedPreferences) {
    private val touched = mutableSetOf<CompressionChoice>()
    private fun key(choice: CompressionChoice) = "compression_default_${choice.name.lowercase(java.util.Locale.ROOT)}"
    fun beginInput() { touched.clear() }
    fun userChanged(choice: CompressionChoice) { touched += choice }
    fun mayRecommend(choice: CompressionChoice): Boolean = choice !in touched && !prefs.contains(key(choice))
    fun load(): CompressionDefaults {
        val quality = runCatching { prefs.getFloat(key(CompressionChoice.QUALITY), 0.5f) }.getOrDefault(0.5f)
        fun boolean(choice: CompressionChoice) = runCatching { prefs.getBoolean(key(choice), false) }.getOrDefault(false)
        return CompressionDefaults(if (quality.isFinite()) quality.coerceIn(0.25f, 1f) else 0.5f,
            boolean(CompressionChoice.GRAYSCALE), boolean(CompressionChoice.LOSSLESS), boolean(CompressionChoice.STRIP_METADATA))
    }
    fun save(value: CompressionDefaults) {
        prefs.edit().putFloat(key(CompressionChoice.QUALITY), value.quality.coerceIn(0.25f, 1f))
            .putBoolean(key(CompressionChoice.GRAYSCALE), value.grayscale)
            .putBoolean(key(CompressionChoice.LOSSLESS), value.lossless)
            .putBoolean(key(CompressionChoice.STRIP_METADATA), value.stripMetadata).apply()
    }
    fun reset() {
        val editor = prefs.edit()
        CompressionChoice.entries.forEach { editor.remove(key(it)) }
        editor.apply()
        touched.clear()
    }
}
