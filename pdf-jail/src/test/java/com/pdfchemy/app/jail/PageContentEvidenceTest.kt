package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.engines.PageContentEvidence
import com.pdfchemy.app.logic.PageStreamEvidence
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import org.junit.Assert.*
import org.junit.Test

class PageContentEvidenceTest {
    private fun content(document: PDDocument, page: PDPage, bytes: String) {
        val stream = PDStream(document)
        stream.createOutputStream().use { it.write(bytes.toByteArray(Charsets.US_ASCII)) }
        page.setContents(stream)
    }

    @Test fun distinguishesMatchingDifferentAndUnmeasuredPages() {
        PDDocument().use { first ->
            PDDocument().use { second ->
                val pages1 = List(3) { PDPage().also(first::addPage) }
                val pages2 = List(3) { PDPage().also(second::addPage) }
                content(first, pages1[0], "q Q")
                content(second, pages2[0], "q Q")
                content(first, pages1[1], "1 0 0 rg")
                content(second, pages2[1], "0 1 0 rg")
                pages2[2].cosObject.setString(COSName.CONTENTS, "not a stream")
                assertEquals(PageStreamEvidence(1, 1, 1),
                    PageContentEvidence.compare(PageContentEvidence.capture(first), PageContentEvidence.capture(second)))
            }
        }
    }

    @Test fun exhaustedBudgetCannotClaimMatchingNonemptyStreams() {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            content(document, page, "q Q")
            val unknown = PageContentEvidence.capture(document, byteBudget = 1)
            assertNull(unknown.single())
            assertEquals(PageStreamEvidence(0, 0, 1), PageContentEvidence.compare(unknown, unknown))
        }
    }

    @Test fun emptyPagesRemainComparableWithoutReadingStreamBytes() {
        PDDocument().use { document ->
            document.addPage(PDPage())
            val blank = PageContentEvidence.capture(document, byteBudget = 0)
            assertNotNull(blank.single())
            assertEquals(PageStreamEvidence(1, 0, 0), PageContentEvidence.compare(blank, blank))
        }
    }
}
