package com.pdfchemy.app.jail

import com.pdfchemy.app.logic.ArchiveInspectContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArchiveClaimSecurityTest {
    private fun report() = JSONObject().put("pdfaVersionDetected", "Unknown (not certified)")
        .put("complianceScore", 100).put("checks", JSONArray())
        .put("isCompliant", false).put("certificationStatus", "NOT_CERTIFIED")
        .put("assessmentScope", "PRELIMINARY_PDF_A_CHECKS")
    private fun reject(value: JSONObject) {
        try { WorkerResponseValidator.validate("ARCHIVE_INSPECT", value.toString()); fail("Certification claim accepted") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun forgedConversionSuccessCannotAuthorizePublication() {
        try { WorkerResponseValidator.validate("ARCHIVE_CONVERT", "{\"outBytes\":10}", 1); fail("Unsupported output accepted") }
        catch (_: UnsupportedOperationException) {}
    }
    @Test fun affirmativeComplianceIsRejected() = reject(report().put("isCompliant", true))
    @Test fun forgedCertificationStatusIsRejected() = reject(report().put("certificationStatus", "CERTIFIED"))
    @Test fun unverifiedVersionClaimIsRejected() = reject(report().put("pdfaVersionDetected", "PDF/A-1b Ready"))
    @Test fun missingCertificationStatusIsRejected() = reject(report().apply { remove("certificationStatus") })
    @Test fun fullPreliminaryPassRateDoesNotBecomeCertification() {
        val value = WorkerResponseValidator.validate("ARCHIVE_INSPECT", report().toString()) as ArchiveInspectContract
        assertEquals(100, value.complianceScore)
        assertFalse(value.isCompliant)
        assertEquals("NOT_CERTIFIED", value.certificationStatus)
    }
}
