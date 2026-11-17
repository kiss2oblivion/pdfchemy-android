package com.pdfchemy.app.logic

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/** Main-thread speech owner. IDs retire callbacks when playback or document ownership changes. */
internal class OfflineReadAloudSession(private val engine: TextToSpeech) {
    companion object { const val INITIALIZATION_TIMEOUT_MILLIS = 30_000L }
    enum class Availability { INITIALIZING, READY, UNAVAILABLE, CLOSED }
    data class State(val availability: Availability = Availability.INITIALIZING,
        val playing: Boolean = false, val paragraphIndex: Int = 0)

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var paragraphs = emptyList<String>()
    private var documentKey: Any? = null
    private var selectedVoice: Voice? = null
    private var activeId: String? = null
    private var pendingIndex: Int? = null
    private var rate = 1.0f

    fun initialize(status: Int, locale: Locale = Locale.getDefault()) {
        if (state.value.availability != Availability.INITIALIZING) return
        selectedVoice = if (status == TextToSpeech.SUCCESS) OfflineSpeechPolicy.configure(engine, locale) else null
        if (selectedVoice == null) { unavailable(); return }
        mutableState.value = state.value.copy(availability = Availability.READY)
        val requested = pendingIndex
        pendingIndex = null
        if (requested != null) play(requested)
    }

    fun replaceParagraphs(value: List<String>, document: Any? = null) {
        if (state.value.availability == Availability.CLOSED || (value == paragraphs && documentKey == document)) return
        stop()
        documentKey = document
        paragraphs = value.toList()
        mutableState.value = state.value.copy(paragraphIndex = 0)
    }

    fun initializationTimedOut() {
        if (state.value.availability == Availability.INITIALIZING) unavailable()
    }

    fun setRate(value: Float) {
        require(value.isFinite() && value > 0)
        rate = value
        if (state.value.availability == Availability.READY) {
            if (runCatching { engine.setSpeechRate(rate) }.getOrDefault(TextToSpeech.ERROR) != TextToSpeech.SUCCESS) unavailable()
        }
    }

    fun play(index: Int): Boolean {
        if (index !in paragraphs.indices) return false
        if (state.value.availability == Availability.INITIALIZING) {
            pendingIndex = index
            mutableState.value = state.value.copy(paragraphIndex = index)
            return false
        }
        if (state.value.availability != Availability.READY) return false
        return try {
            val voice = selectedVoice
            if (voice == null || !OfflineSpeechPolicy.matches(engine.voice, voice) ||
                engine.setSpeechRate(rate) != TextToSpeech.SUCCESS) {
                unavailable()
                false
            } else {
                val id = UUID.randomUUID().toString()
                activeId = id
                mutableState.value = state.value.copy(playing = true, paragraphIndex = index)
                if (engine.speak(paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) true
                else { unavailable(); false }
            }
        } catch (_: Exception) { unavailable(); false }
    }

    fun onStart(id: String?): Boolean = state.value.availability == Availability.READY &&
        state.value.playing && id != null && id == activeId

    fun onDone(id: String?) {
        if (!onStart(id)) return
        activeId = null
        val next = state.value.paragraphIndex + 1
        if (next in paragraphs.indices) play(next)
        else mutableState.value = state.value.copy(playing = false)
    }

    fun onError(id: String?) { if (onStart(id)) unavailable() }

    fun stop() {
        if (state.value.availability == Availability.CLOSED) return
        activeId = null
        pendingIndex = null
        mutableState.value = state.value.copy(playing = false)
        runCatching { engine.stop() }
    }

    private fun unavailable() {
        stop()
        selectedVoice = null
        mutableState.value = state.value.copy(availability = Availability.UNAVAILABLE)
    }

    fun close() {
        if (state.value.availability == Availability.CLOSED) return
        stop()
        mutableState.value = state.value.copy(availability = Availability.CLOSED)
        runCatching { engine.shutdown() }
    }
}
