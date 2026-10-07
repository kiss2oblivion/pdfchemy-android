package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {
    const val MAX_PCM_BYTES = 0xffff_ffffL - 37
    fun header(format: PcmFormat, dataBytes: Long): ByteArray {
        require(dataBytes in 1..MAX_PCM_BYTES && dataBytes % format.blockAlign == 0L) { "Invalid PCM length" }
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + dataBytes + (dataBytes and 1)).toInt())
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(format.channels.toShort()); putInt(format.sampleRate)
            putInt(format.byteRate.toInt()); putShort(format.blockAlign.toShort()); putShort(format.bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes.toInt())
        }.array()
    }

    suspend fun assemble(raw: File, wav: File, format: PcmFormat) {
        val length = raw.length()
        try {
            wav.outputStream().use { output ->
                output.write(header(format, length))
                raw.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count <= length - copied) { "PCM changed during assembly" }
                        output.write(buffer, 0, count); copied += count
                    }
                    check(copied == length)
                }
                if (length and 1L != 0L) output.write(0)
            }
            check(wav.length() == 44 + length + (length and 1))
        } catch (error: Throwable) { wav.delete(); throw error }
    }
}
