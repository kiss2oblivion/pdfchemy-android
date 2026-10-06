package com.pdfchemy.app.logic.audio

import android.speech.tts.UtteranceProgressListener

/** Android callback adapter; all identity, format and drain policy lives in the tested writer. */
class TtsPcmAssembler(private val writer: PcmWriter) : UtteranceProgressListener() {
    override fun onStart(utteranceId: String) = Unit
    override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) =
        writer.begin(utteranceId, sampleRateInHz, audioFormat, channelCount)
    override fun onAudioAvailable(utteranceId: String, audio: ByteArray) = writer.audio(utteranceId, audio)
    override fun onDone(utteranceId: String) = writer.done(utteranceId)
    override fun onError(utteranceId: String) = writer.error(utteranceId, IllegalStateException("TTS synthesis failed"))
    override fun onError(utteranceId: String, errorCode: Int) =
        writer.error(utteranceId, IllegalStateException("TTS synthesis failed ($errorCode)"))
    override fun onStop(utteranceId: String, interrupted: Boolean) =
        writer.error(utteranceId, IllegalStateException("TTS synthesis stopped"))
}
