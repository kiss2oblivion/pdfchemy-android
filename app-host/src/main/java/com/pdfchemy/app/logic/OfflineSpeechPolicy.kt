package com.pdfchemy.app.logic

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

internal class OfflineSpeechUnavailableException : IllegalStateException("No installed offline voice is ready for the current language")

/** Enforces the installed engine's offline declaration; never selects a downloadable voice. */
internal object OfflineSpeechPolicy {
    private fun offline(voice: Voice): Boolean = !voice.isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()

    fun selectVoice(voices: Collection<Voice>, locale: Locale): Voice? {
        val installed = voices.filter(::offline).sortedBy { it.name }
        return installed.firstOrNull { it.locale == locale }
            ?: installed.firstOrNull { it.locale.language == locale.language }
    }

    fun matches(current: Voice?, selected: Voice): Boolean = current != null &&
        offline(current) && offline(selected) && current.name == selected.name && current.locale == selected.locale

    fun configure(engine: TextToSpeech, locale: Locale = Locale.getDefault()): Voice? = try {
        val voice = selectVoice(engine.voices.orEmpty(), locale)
        if (voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS && matches(engine.voice, voice)) voice else null
    } catch (_: Exception) { null }
}
