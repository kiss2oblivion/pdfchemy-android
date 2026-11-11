package com.pdfchemy.app.logic.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream

/**
 * Encodes uncompressed PCM audio into an MPEG-4 AAC (.m4a) container using Android's
 * native MediaCodec hardware encoder and MediaMuxer.
 *
 * Compresses raw 16-bit PCM (~10.5 MB/min) down to ~0.9 MB/min (128 kbps AAC-LC),
 * delivering ~90% storage savings for audiobooks and documents while maintaining high fidelity.
 */
object AacM4aWriter {
    private const val TIMEOUT_US = 10_000L
    private const val BITRATE = 128_000 // 128 kbps AAC-LC

    fun isCodecAvailable(): Boolean = runCatching {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        codecList.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { type ->
                type.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
            }
        }
    }.getOrDefault(false)

    suspend fun assemble(raw: File, m4a: File, format: PcmFormat) {
        require(raw.exists() && raw.length() > 0) { "Raw audio file is missing or empty" }
        val sampleRate = format.sampleRate
        val channelCount = format.channels

        val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var fis: FileInputStream? = null

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(m4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1
            var muxerStarted = false

            fis = raw.inputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            val inputChunk = ByteArray(8192)
            var isInputEos = false
            var totalFramesRead = 0L
            val rawLength = raw.length()
            var totalBytesRead = 0L

            while (true) {
                currentCoroutineContext().ensureActive()

                // Feed input to encoder
                if (!isInputEos) {
                    val inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            val maxToRead = minOf(inputChunk.size, inputBuffer.remaining())
                            val readCount = fis.read(inputChunk, 0, maxToRead)
                            if (readCount < 0) {
                                encoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEos = true
                            } else {
                                inputBuffer.put(inputChunk, 0, readCount)
                                val ptsUs = (totalFramesRead * 1_000_000L) / sampleRate
                                totalFramesRead += (readCount / format.blockAlign)
                                totalBytesRead += readCount
                                val flags = if (totalBytesRead >= rawLength) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                                encoder.queueInputBuffer(inputBufIndex, 0, readCount, ptsUs, flags)
                                if (flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                    isInputEos = true
                                }
                            }
                        }
                    }
                }

                // Drain output from encoder
                val outputBufIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw IllegalStateException("AAC encoder format changed unexpectedly")
                    val newFormat = encoder.outputFormat
                    audioTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outputBufIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputBufIndex)
                    if (outputBuffer != null && muxerStarted) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }

            check(m4a.exists() && m4a.length() > 0) { "M4A assembly resulted in an empty file" }
        } catch (t: Throwable) {
            m4a.delete()
            throw t
        } finally {
            try { fis?.close() } catch (_: Throwable) {}
            try {
                encoder?.stop()
                encoder?.release()
            } catch (_: Throwable) {}
            try {
                if (muxer != null) {
                    muxer.stop()
                    muxer.release()
                }
            } catch (_: Throwable) {}
        }
    }
}

