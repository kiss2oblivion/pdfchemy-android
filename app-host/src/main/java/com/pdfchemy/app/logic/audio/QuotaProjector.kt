package com.pdfchemy.app.logic.audio

import kotlin.math.ceil

class QuotaProjector(private val totalCharacters: Int) {
    init { require(totalCharacters > 0) }
    private var characters = 0
    private var bytes = 0L
    private var seconds = 0.0

    fun recordChunk(chunkChars: Int, chunkBytes: Long, format: PcmFormat) {
        require(chunkChars > 0 && chunkChars <= totalCharacters - characters)
        require(chunkBytes > 0 && chunkBytes % format.blockAlign == 0L)
        characters += chunkChars
        bytes = Math.addExact(bytes, chunkBytes)
        seconds += chunkBytes.toDouble() / format.byteRate
    }

    fun projectedBytes(format: PcmFormat): Long {
        if (characters == 0) return 0
        val remainingSeconds = (totalCharacters - characters) * (seconds / characters)
        val remainingBytes = ceil(remainingSeconds * format.byteRate)
        if (!remainingBytes.isFinite() || remainingBytes >= Long.MAX_VALUE - bytes) return Long.MAX_VALUE
        return bytes + remainingBytes.toLong()
    }

    fun isSafe(format: PcmFormat, availableBytes: Long, reserveBytes: Long = FILESYSTEM_RESERVE): Boolean {
        val projected = projectedBytes(format)
        if (projected > WavWriter.MAX_PCM_BYTES || availableBytes <= reserveBytes) return false
        // Raw + WAV + disposable engine sink can coexist. Reserve is free-space headroom, not an output quota.
        val additional = maxOf(0, projected - bytes)
        return projected <= (availableBytes - reserveBytes - minOf(additional, availableBytes)) / 2
    }
    companion object { const val FILESYSTEM_RESERVE = 32L * 1024 * 1024 }
}
