package com.pdfchemy.app.security

import java.io.FilterOutputStream
import java.io.OutputStream

class BoundedOutputStream(out: OutputStream, private val limit: Long = SecurityLimits.MAX_OUTPUT_BYTES) : FilterOutputStream(out) {
    private var count = 0L
    private fun reserve(bytes: Int) {
        if (bytes < 0 || bytes.toLong() > limit - count) throw SecurityException("Output byte quota exceeded")
        count += bytes
    }
    override fun write(value: Int) { reserve(1); out.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); out.write(bytes, offset, length) }
}
