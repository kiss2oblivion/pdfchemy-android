package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.XrefObservation
import com.pdfchemy.app.logic.XrefObservationKind
import org.json.JSONObject

/** Bounded raw-byte observation, not a cross-reference parser or security check. */
internal object XrefDiagnostics {
    private const val TAIL_BYTES = 64 * 1024
    private const val TARGET_BYTES = 128
    private fun Char.pdfSpace() = this == '\u0000' || this == '\t' ||
        this == '\n' || this == '\u000C' || this == '\r' || this == ' '
    private val objectHeader = Regex("^[0-9]{1,10}[\\x00\\t\\n\\f\\r ]+[0-9]{1,5}[\\x00\\t\\n\\f\\r ]+obj(?=[\\x00\\t\\n\\f\\r <]|$)")

    fun inspect(size: Long, readAt: (Long, Int) -> ByteArray): XrefObservation {
        require(size > 0)
        val length = minOf(size, TAIL_BYTES.toLong()).toInt()
        val start = size - length
        val tail = readAt(start, length).toString(Charsets.ISO_8859_1)
        require(tail.length == length) { "Incomplete diagnostic read" }
        val end = tail.indexOfLast { !it.pdfSpace() } + 1
        val eof = end - 5
        if (eof < 0 || !tail.regionMatches(eof, "%%EOF", 0, 5) ||
            (eof > 0 && !tail[eof - 1].pdfSpace())) {
            return XrefObservation(XrefObservationKind.TERMINAL_EOF_NOT_FOUND)
        }
        val marker = tail.lastIndexOf("startxref", eof - 1)
        if (marker < 0 || (marker > 0 && !tail[marker - 1].pdfSpace())) {
            return XrefObservation(XrefObservationKind.STARTXREF_NOT_FOUND)
        }
        val valueStart = marker + "startxref".length
        if (valueStart >= eof || !tail[valueStart].pdfSpace() || !tail[eof - 1].pdfSpace())
            return XrefObservation(XrefObservationKind.OFFSET_MALFORMED)
        val token = tail.substring(valueStart, eof).trim { it.pdfSpace() }
        if (token.isEmpty() || token.length > 19 || token.any { it !in '0'..'9' })
            return XrefObservation(XrefObservationKind.OFFSET_MALFORMED)
        val offset = token.toLongOrNull() ?: return XrefObservation(XrefObservationKind.OFFSET_MALFORMED)
        // The latest target must precede its own startxref marker.
        if (offset >= size || offset >= start + marker)
            return XrefObservation(XrefObservationKind.OFFSET_OUT_OF_RANGE, offset)
        val count = minOf(TARGET_BYTES.toLong(), size - offset).toInt()
        val target = readAt(offset, count).toString(Charsets.ISO_8859_1)
        require(target.length == count) { "Incomplete diagnostic target read" }
        val kind = when {
            target.startsWith("xref") && target.length > 4 && target[4].pdfSpace() ->
                XrefObservationKind.TABLE_MARKER
            objectHeader.containsMatchIn(target) -> XrefObservationKind.INDIRECT_OBJECT_MARKER
            else -> XrefObservationKind.UNRECOGNIZED_TARGET
        }
        return XrefObservation(kind, offset)
    }

    fun json(observation: XrefObservation): JSONObject =
        JSONObject().put("kind", observation.kind.name).apply {
            observation.offset?.let { put("offset", it) }
        }
}
