package com.pdfchemy.app.jail.engines

import org.json.JSONObject

/** Only emit after the serialized candidate has passed page-parity and Vanguard checks.
 * The original input may have been partially readable; no claim of complete content preservation. */
internal fun recoveryAccounting(
    method: String, pages: Int, headerInserted: Boolean = false,
    prefixBytesRemoved: Long = 0L, eofAppended: Boolean = false,
    activeFindingsBefore: Int? = null
): JSONObject = JSONObject()
    .put("method", method)
    .put("readableInputPages", pages)
    .put("verifiedOutputPages", pages)
    .put("headerInserted", headerInserted)
    .put("prefixBytesRemoved", prefixBytesRemoved)
    .put("eofAppended", eofAppended)
    .put("activeFindingsAfter", 0)
    .apply { activeFindingsBefore?.let { put("activeFindingsBefore", it) } }

internal fun ActiveContentScrubber.Findings.executableFindingCount(): Int =
    javascript + launches + actions + untrustedUris
