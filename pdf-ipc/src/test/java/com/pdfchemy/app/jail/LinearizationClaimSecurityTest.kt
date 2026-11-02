package com.pdfchemy.app.jail

import com.pdfchemy.app.logic.LinearizeCheckContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinearizationClaimSecurityTest {
    @Test fun forgedOutputSuccessIsNeverAcceptedForPublication() {
        try {
            WorkerResponseValidator.validate("LINEARIZE_OPTIMIZE", "{\"outBytes\":7}", 1)
            fail("An unsupported engine may not authorize destination publication")
        } catch (_: UnsupportedOperationException) {}
    }
    @Test fun affirmativeWorkerClaimIsRejected() {
        try {
            WorkerResponseValidator.validate("LINEARIZE_CHECK", "{\"isLinearized\":true,\"verificationStatus\":\"NOT_VERIFIED\",\"pageCount\":1}")
            fail("Unverifiable affirmative claim accepted")
        } catch (_: IllegalArgumentException) {}
    }
    @Test fun explicitUnverifiedDiagnosticIsAccepted() {
        val report = WorkerResponseValidator.validate("LINEARIZE_CHECK", "{\"isLinearized\":false,\"verificationStatus\":\"NOT_VERIFIED\",\"pageCount\":1}") as LinearizeCheckContract
        assertFalse(report.isLinearized)
        assertEquals("NOT_VERIFIED", report.verificationStatus)
    }
    @Test fun missingVerificationStatusIsRejected() {
        try {
            WorkerResponseValidator.validate("LINEARIZE_CHECK", "{\"isLinearized\":false,\"pageCount\":1}")
            fail("Ambiguous diagnostic accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
