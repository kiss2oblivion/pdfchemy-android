package com.pdfchemy.app.logic.audio

data class PcmFormat(val sampleRate: Int, val channels: Int, val encoding: Int) {
    init {
        require(sampleRate in 8000..192000) { "Unsupported PCM sample rate" }
        require(channels in 1..2) { "Only mono/stereo PCM is supported" }
        // AudioFormat: 2 = signed little-endian PCM16, 3 = unsigned PCM8.
        // Float (4) deliberately fails; no implicit conversion or mislabeled WAV.
        require(encoding == 2 || encoding == 3) { "Unsupported PCM encoding (float is not supported)" }
    }
    val bytesPerSample: Int get() = if (encoding == 2) 2 else 1
    val blockAlign: Int get() = channels * bytesPerSample
    val byteRate: Long get() = sampleRate.toLong() * blockAlign
    val bitsPerSample: Int get() = bytesPerSample * 8
}
