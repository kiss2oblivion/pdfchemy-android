package com.pdfchemy.app.logic.audio

import android.media.AudioFormat
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

sealed class PcmMessage {
    class AudioData(val bytes: ByteArray, val utteranceId: String) : PcmMessage()
    class FlushBarrier(val chunkId: String, val ack: CompletableDeferred<Unit>) : PcmMessage()
}

class TtsPcmAssembler(
    private val jobId: UUID,
    private val masterRawFile: File,
    private val jobStore: AudioExportJobStore,
    private val coroutineScope: CoroutineScope,
    private val onChunkComplete: (String) -> Unit,
    private val onError: (String, String) -> Unit
) : UtteranceProgressListener() {

    private var sampleRate: Int = -1
    private var audioFormat: Int = -1
    private var channelCount: Int = -1

    private val pcmChannel = Channel<PcmMessage>(Channel.UNLIMITED)
    private var isWriterActive = false
    private var writerException: Throwable? = null

    init {
        startWriterThread()
    }

    private fun startWriterThread() {
        if (isWriterActive) return
        isWriterActive = true
        coroutineScope.launch {
            try {
                FileOutputStream(masterRawFile, true).use { fos ->
                    for (msg in pcmChannel) {
                        val job = jobStore.getJob(jobId)
                        if (job?.state == AudioExportState.CANCELLING || job?.state == AudioExportState.CANCELLED) {
                            // Job is cancelled, stop writing and acknowledge pending barriers
                            if (msg is PcmMessage.FlushBarrier) {
                                msg.ack.complete(Unit)
                            }
                            continue
                        }
                        
                        when (msg) {
                            is PcmMessage.AudioData -> {
                                fos.write(msg.bytes)
                            }
                            is PcmMessage.FlushBarrier -> {
                                fos.flush()
                                msg.ack.complete(Unit)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                writerException = e
            } finally {
                isWriterActive = false
            }
        }
    }

    override fun onStart(utteranceId: String) {
        // No-op
    }

    override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
        if (this.sampleRate == -1) {
            this.sampleRate = sampleRateInHz
            this.audioFormat = audioFormat
            this.channelCount = channelCount

            val supportedFormats = listOf(
                AudioFormat.ENCODING_PCM_8BIT,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            if (audioFormat !in supportedFormats) {
                val errorMsg = "Unsupported PCM audio format: $audioFormat"
                jobStore.updateState(jobId, AudioExportState.FAILED, Exception(errorMsg))
                onError(utteranceId, errorMsg)
            }
        } else {
            if (this.sampleRate != sampleRateInHz || this.audioFormat != audioFormat || this.channelCount != channelCount) {
                val errorMsg = "Audio format changed during synthesis!"
                jobStore.updateState(jobId, AudioExportState.FAILED, Exception(errorMsg))
                onError(utteranceId, errorMsg)
            }
        }
    }

    override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
        val job = jobStore.getJob(jobId)
        if (job == null || job.state == AudioExportState.CANCELLING || job.state == AudioExportState.CANCELLED) {
            return
        }

        pcmChannel.trySend(PcmMessage.AudioData(audio.clone(), utteranceId))
    }

    override fun onDone(utteranceId: String) {
        coroutineScope.launch {
            val ack = CompletableDeferred<Unit>()
            pcmChannel.send(PcmMessage.FlushBarrier(utteranceId, ack))
            ack.await()
            onChunkComplete(utteranceId)
        }
    }

    override fun onError(utteranceId: String) {
        onError(utteranceId, "Unknown TTS Error")
    }

    @Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId)"))
    override fun onError(utteranceId: String, errorCode: Int) {
        onError(utteranceId, "TTS Error Code: $errorCode")
    }

    override fun onStop(utteranceId: String, interrupted: Boolean) {
        // TTS synthesis was stopped/interrupted.
    }

    fun close() {
        pcmChannel.close()
    }

    fun finishAndWriteWavHeader(finalWavFile: File) {
        pcmChannel.close()
        
        if (sampleRate == -1 || !masterRawFile.exists()) {
            throw IllegalStateException("No audio was synthesized.")
        }

        val rawLength = masterRawFile.length()
        
        val bitsPerSample = when (audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

        val byteRate = sampleRate * channelCount * bitsPerSample / 8
        val blockAlign = channelCount * bitsPerSample / 8
        val dataSize = rawLength
        val chunkSize = 36 + dataSize

        FileOutputStream(finalWavFile).use { fos ->
            fos.write("RIFF".toByteArray(Charsets.US_ASCII))
            fos.write(intToByteArray(chunkSize.toInt()))
            fos.write("WAVE".toByteArray(Charsets.US_ASCII))

            fos.write("fmt ".toByteArray(Charsets.US_ASCII))
            fos.write(intToByteArray(16))
            
            val audioFormatCode = if (audioFormat == AudioFormat.ENCODING_PCM_FLOAT) 3 else 1
            fos.write(shortToByteArray(audioFormatCode.toShort()))
            fos.write(shortToByteArray(channelCount.toShort()))
            fos.write(intToByteArray(sampleRate))
            fos.write(intToByteArray(byteRate))
            fos.write(shortToByteArray(blockAlign.toShort()))
            fos.write(shortToByteArray(bitsPerSample.toShort()))

            fos.write("data".toByteArray(Charsets.US_ASCII))
            fos.write(intToByteArray(dataSize.toInt()))
        }

        masterRawFile.inputStream().use { input ->
            FileOutputStream(finalWavFile, true).use { output ->
                input.copyTo(output)
            }
        }
        
        masterRawFile.delete()
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            (value shr 8 and 0xff).toByte(),
            (value shr 16 and 0xff).toByte(),
            (value shr 24 and 0xff).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xff).toByte(),
            (value.toInt() shr 8 and 0xff).toByte()
        )
    }

    fun getCalculatedBytesPerSample(): Int {
        val bits = when (audioFormat) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
        return channelCount * bits / 8
    }
    
    fun getSampleRate(): Int = sampleRate
    
    fun getChannelCount(): Int = channelCount
}
