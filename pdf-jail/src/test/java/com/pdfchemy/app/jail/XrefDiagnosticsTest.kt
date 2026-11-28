package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.engines.XrefDiagnostics
import com.pdfchemy.app.logic.XrefObservationKind.*
import org.junit.Assert.*
import org.junit.Test

class XrefDiagnosticsTest {
    private fun inspect(text: String) = text.toByteArray(Charsets.ISO_8859_1).let { bytes ->
        XrefDiagnostics.inspect(bytes.size.toLong()) { offset, size ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
        }
    }

    @Test fun recognizesClassicTableMarkerWithoutClaimingEntryValidity() {
        val prefix = "%PDF-1.7\n"
        val result = inspect(prefix + "xref\n0 1\nstartxref\n" + prefix.length + "\n%%EOF\n")
        assertEquals(TABLE_MARKER, result.kind)
        assertEquals(prefix.length.toLong(), result.offset)
    }

    @Test fun indirectObjectIsOnlyAStreamCandidate() {
        val prefix = "%PDF-1.7\n"
        val result = inspect(prefix + "9 0 obj\n<< /Type /Other >>\nendobj\nstartxref\n" + prefix.length + "\n%%EOF\n")
        assertEquals(INDIRECT_OBJECT_MARKER, result.kind)
    }

    @Test fun rejectsMalformedAndOverflowingOffsets() {
        for (value in listOf("-1", "1.5", "abc", "9223372036854775808")) {
            assertEquals(OFFSET_MALFORMED, inspect("startxref\n$value\n%%EOF\n").kind)
        }
    }

    @Test fun outOfRangeAndWrongTargetAreSeparate() {
        assertEquals(OFFSET_OUT_OF_RANGE, inspect("%PDF-1.7\nstartxref\n9999\n%%EOF\n").kind)
        assertEquals(UNRECOGNIZED_TARGET, inspect("%PDF-1.7\nstartxref\n0\n%%EOF\n").kind)
    }

    @Test fun missingTerminalMarkersDoNotClaimCorruption() {
        assertEquals(TERMINAL_EOF_NOT_FOUND, inspect("startxref\n0\n%%EOF\ntrailing data").kind)
        assertEquals(STARTXREF_NOT_FOUND, inspect("%PDF-1.7\n%%EOF\n").kind)
    }

    @Test fun latestRevisionWinsAndReadsStayBounded() {
        val prefix = "%PDF-1.7\n"
        val old = prefix + "xref\nstartxref\n" + prefix.length + "\n%%EOF\n"
        val target = old + " ".repeat(80_000) + "\n"
        val bytes = (target + "xref\nstartxref\n" + target.length + "\n%%EOF\n").toByteArray()
        val reads = mutableListOf<Int>()
        val result = XrefDiagnostics.inspect(bytes.size.toLong()) { offset, size ->
            reads.add(size)
            bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
        }
        assertEquals(TABLE_MARKER, result.kind)
        assertEquals(target.length.toLong(), result.offset)
        assertEquals(2, reads.size)
        assertTrue(reads.sum() <= 64 * 1024 + 128)
    }
}
