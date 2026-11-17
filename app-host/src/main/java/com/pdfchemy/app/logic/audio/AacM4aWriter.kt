package com.pdfchemy.app.logic.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** Encodes complete PCM16 frames using the platform AAC encoder and MP4 muxer. */
object AacM4aWriter {
    private const val TIMEOUT_US = 10_000L
    private const val STALL_TIMEOUT_NS = 30_000_000_000L
    private const val BITRATE = 128_000

    fun isCodecAvailable(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true)
            }
        }
    }.getOrDefault(false)

    suspend fun assemble(raw: File, m4a: File, format: PcmFormat) {
        currentCoroutineContext().ensureActive()
        require(raw.exists() && raw.length() > 0) { "Raw audio file is missing or empty" }
        require(raw.canonicalFile != m4a.canonicalFile) { "Audio output must differ from its source" }
        require(!m4a.exists() || m4a.length() == 0L) { "Audio output must be a fresh staging file" }
        require(format.encoding == 2) { "AAC export requires PCM16; use WAV for PCM8" }
        val rawLength = raw.length()
        require(rawLength % format.blockAlign == 0L) { "Incomplete PCM frame" }

        val mediaFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var complete = false
        try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder = codec
            codec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val container = MediaMuxer(m4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = container
            var track = -1
            var inputEos = false
            var consumed = 0L
            var samples = 0
            var lastProgress = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            val bytes = ByteArray(8192)
            raw.inputStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() - lastProgress > STALL_TIMEOUT_NS) {
                        throw IOException("AAC encoder stopped making progress")
                    }
                    if (!inputEos) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = checkNotNull(codec.getInputBuffer(index))
                            buffer.clear()
                            val capacity = minOf(bytes.size, buffer.remaining())
                            val alignedCapacity = capacity / format.blockAlign * format.blockAlign
                            check(alignedCapacity > 0) { "AAC input buffer cannot fit one PCM frame" }
                            val wanted = minOf(alignedCapacity.toLong(), rawLength - consumed).toInt()
                            var read = 0
                            while (read < wanted) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(bytes, read, wanted - read)
                                check(count > 0) { "PCM source ended before its declared length" }
                                read += count
                            }
                            buffer.put(bytes, 0, read)
                            val pts = consumed / format.blockAlign * 1_000_000L / format.sampleRate
                            consumed += read
                            inputEos = consumed == rawLength
                            codec.queueInputBuffer(index, 0, read, pts,
                                if (inputEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            lastProgress = System.nanoTime()
                        }
                    }
                    val output = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "AAC output format changed twice" }
                        track = container.addTrack(codec.outputFormat)
                        container.start()
                        muxerStarted = true
                        lastProgress = System.nanoTime()
                    } else if (output >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                check(muxerStarted) { "AAC data arrived before its track format" }
                                val buffer = checkNotNull(codec.getOutputBuffer(output))
                                require(info.offset >= 0 && info.size <= buffer.capacity() - info.offset) {
                                    "Invalid AAC output range"
                                }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                container.writeSampleData(track, buffer, info)
                                samples++
                            }
                        } finally {
                            codec.releaseOutputBuffer(output, false)
                        }
                        lastProgress = System.nanoTime()
                        if (eos) break
                    }
                }
            }
            check(inputEos && consumed == rawLength && samples > 0 && muxerStarted) {
                "AAC encoder returned incomplete audio"
            }
            container.stop()
            muxerStarted = false
            check(m4a.isFile && m4a.length() > 0) { "M4A assembly produced an empty file" }
            complete = true
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!complete) m4a.delete()
        }
    }
}
