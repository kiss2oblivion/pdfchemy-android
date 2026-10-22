package com.pdfchemy.app.logic

import com.pdfchemy.app.security.SecurityLimits
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Enforces strict streaming byte and character admission limits for Markdown and text imports (SC-08).
 * Prevents host heap exhaustion (OOM) and endless-stream denial of service before materializing strings.
 */
object MarkdownAdmission {
    const val DEFAULT_MAX_BYTES: Long = SecurityLimits.MAX_TEXT_BYTES.toLong() // 2 MB
    const val DEFAULT_MAX_CHARS: Int = 1_000_000 // 1 million characters

    suspend fun readBoundedText(
        stream: InputStream,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        maxChars: Int = DEFAULT_MAX_CHARS,
        charset: Charset = Charsets.UTF_8
    ): String {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxChars > 0) { "maxChars must be positive" }

        var bytesRead = 0L
        val countingStream = object : InputStream() {
            override fun read(): Int {
                if (bytesRead > maxBytes) {
                    throw SecurityException("Markdown input exceeds byte budget limit of $maxBytes bytes")
                }
                val b = stream.read()
                if (b != -1) {
                    bytesRead++
                    if (bytesRead > maxBytes) {
                        throw SecurityException("Markdown input exceeds byte budget limit of $maxBytes bytes")
                    }
                }
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (bytesRead > maxBytes) {
                    throw SecurityException("Markdown input exceeds byte budget limit of $maxBytes bytes")
                }
                val remaining = maxBytes - bytesRead
                val allowed = if (remaining > 0) {
                    minOf(len.toLong(), remaining).toInt()
                } else {
                    1 // Probe 1 byte to check for overflow vs EOF
                }
                val readCount = stream.read(b, off, allowed)
                if (readCount != -1) {
                    bytesRead += readCount
                    if (bytesRead > maxBytes) {
                        throw SecurityException("Markdown input exceeds byte budget limit of $maxBytes bytes")
                    }
                }
                return readCount
            }

            override fun close() {
                stream.close()
            }
        }

        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val reader = InputStreamReader(countingStream, decoder)
        val buffer = CharArray(4096)
        val sb = StringBuilder()

        while (true) {
            currentCoroutineContext().ensureActive()
            val charsRead = reader.read(buffer)
            if (charsRead == -1) break
            if (sb.length + charsRead > maxChars) {
                throw SecurityException("Markdown input exceeds character limit of $maxChars characters")
            }
            sb.append(buffer, 0, charsRead)
        }
        return sb.toString()
    }
}
