package com.pdfchemy.app.jail

import com.pdfchemy.app.security.SecurityLimits
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RequestValidatorTest {
    private fun rejected(json: String) {
        try { RequestValidator.validate(json); fail("Excessive parameters were accepted") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun bookmarkArrayContractRetainsTheSameBounds() {
        RequestValidator.validate("""[{"title":"Chapter","pageIndex":0}]""")
        rejected("[" + List(SecurityLimits.MAX_BOOKMARKS + 1) { "{}" }.joinToString(",") + "]")
    }
    @Test fun deepJsonIsRejectedBeforeTheRecursiveParserRuns() {
        rejected("[".repeat(10_000) + "0" + "]".repeat(10_000))
    }
    @Test fun escapedBracketsInTextDoNotCountAsNesting() {
        RequestValidator.validate("""{"text":"[[[ \"quote\" }}}"}""")
    }
    @Test fun oversizedStringsAndTrailingPayloadsAreRejected() {
        rejected("{\"title\":\"${"x".repeat(SecurityLimits.MAX_METADATA_LENGTH + 1)}\"}")
        rejected("{} {}")
    }
}
