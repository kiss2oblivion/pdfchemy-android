package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.engines.ReadablePageEvidence
import com.pdfchemy.app.logic.PageStreamEvidence
import com.pdfchemy.app.logic.PageTextEvidence
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.*
import org.junit.Test

class ReadablePageEvidenceTest {
    @Test fun noTextIsNotAClaimOfMatchingText() {
        PDDocument().use { doc ->
            repeat(2) { doc.addPage(PDPage()) }
            val snapshot = ReadablePageEvidence.capture(doc)
            assertEquals(PageTextEvidence(0, 0, 2, 0),
                ReadablePageEvidence.compareText(snapshot.text, snapshot.text))
        }
    }

    @Test fun comparisonSeparatesTextDifferencesAndUnknownSamples() {
        fun sample(n: Int, hasText: Boolean = true) =
            ReadablePageEvidence.TextSample(byteArrayOf(n.toByte()), hasText)
        val input = listOf(sample(1), sample(2), sample(3, false), sample(4), null)
        val output = listOf(sample(1), sample(7), sample(8, false), sample(4, false), sample(5))
        assertEquals(PageTextEvidence(1, 2, 1, 1), ReadablePageEvidence.compareText(input, output))
    }

    @Test fun geometryIncludesBoxOriginsRotationAndUserUnit() {
        PDDocument().use { doc ->
            repeat(4) { doc.addPage(PDPage(PDRectangle.LETTER)) }
            val before = ReadablePageEvidence.capture(doc)
            doc.getPage(0).cropBox = PDRectangle(5f, 10f, 400f, 500f)
            doc.getPage(1).rotation = 90
            doc.getPage(2).cosObject.setFloat(COSName.USER_UNIT, 2f)
            val after = ReadablePageEvidence.capture(doc)
            assertEquals(PageStreamEvidence(1, 3, 0), ReadablePageEvidence.compareGeometry(before, after))
        }
    }

    @Test fun pagesBeyondEvidenceLimitAreUnknownEvenIfBlank() {
        PDDocument().use { doc ->
            repeat(501) { doc.addPage(PDPage()) }
            val snapshot = ReadablePageEvidence.capture(doc)
            assertNull(snapshot.text[500])
            assertEquals(PageTextEvidence(0, 0, 500, 1),
                ReadablePageEvidence.compareText(snapshot.text, snapshot.text))
        }
    }
}
