package com.pdfchemy.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfDiffEngineTest {

    @Test
    fun testAlignPages_identicalDocuments_matchesSequential() {
        val doc1 = listOf(
            "Chapter 1 Introduction to quantum physics and wave mechanics",
            "Chapter 2 The Schrodinger equation and wave function collapse",
            "Chapter 3 Quantum entanglement and Bell theorem"
        )
        val doc2 = listOf(
            "Chapter 1 Introduction to quantum physics and wave mechanics",
            "Chapter 2 The Schrodinger equation and wave function collapse",
            "Chapter 3 Quantum entanglement and Bell theorem"
        )

        val alignment = PdfDiffEngine.alignPages(doc1, doc2)

        assertEquals(3, alignment.size)
        assertEquals(0, alignment[0].doc1PageIndex)
        assertEquals(0, alignment[0].doc2PageIndex)
        assertEquals(1, alignment[1].doc1PageIndex)
        assertEquals(1, alignment[1].doc2PageIndex)
        assertEquals(2, alignment[2].doc1PageIndex)
        assertEquals(2, alignment[2].doc2PageIndex)
        assertTrue(alignment[0].similarity > 0.9f)
    }

    @Test
    fun testAlignPages_insertedCoverPage_detectsInsertionAndAlignsSubsequent() {
        val doc1 = listOf(
            "Introduction to cybernetic network resilience protocols",
            "Section 2 Architecture overview of distributed microkernels",
            "Section 3 Security considerations and threat analysis"
        )
        val doc2 = listOf(
            "CONFIDENTIAL COMPANY REPORT COVER PAGE 2026",
            "Introduction to cybernetic network resilience protocols",
            "Section 2 Architecture overview of distributed microkernels",
            "Section 3 Security considerations and threat analysis"
        )

        val alignment = PdfDiffEngine.alignPages(doc1, doc2)

        // Expected: (null, 0), (0, 1), (1, 2), (2, 3)
        assertEquals(4, alignment.size)
        assertNull(alignment[0].doc1PageIndex)
        assertEquals(0, alignment[0].doc2PageIndex) // Cover page inserted in doc2

        assertEquals(0, alignment[1].doc1PageIndex)
        assertEquals(1, alignment[1].doc2PageIndex) // Intro matched

        assertEquals(1, alignment[2].doc1PageIndex)
        assertEquals(2, alignment[2].doc2PageIndex) // Section 2 matched

        assertEquals(2, alignment[3].doc1PageIndex)
        assertEquals(3, alignment[3].doc2PageIndex) // Section 3 matched
    }

    @Test
    fun testAlignPages_deletedPage_detectsDeletionAndAlignsRemaining() {
        val doc1 = listOf(
            "Title: Modern compiler design and AST representations",
            "Obsolete deprecated appendix that will be removed next release",
            "Bibliography and academic reference citations"
        )
        val doc2 = listOf(
            "Title: Modern compiler design and AST representations",
            "Bibliography and academic reference citations"
        )

        val alignment = PdfDiffEngine.alignPages(doc1, doc2)

        // Expected: (0, 0), (1, null), (2, 1)
        assertEquals(3, alignment.size)
        assertEquals(0, alignment[0].doc1PageIndex)
        assertEquals(0, alignment[0].doc2PageIndex)

        assertEquals(1, alignment[1].doc1PageIndex)
        assertNull(alignment[1].doc2PageIndex) // Deprecated appendix deleted

        assertEquals(2, alignment[2].doc1PageIndex)
        assertEquals(1, alignment[2].doc2PageIndex) // Bibliography matched
    }

    @Test
    fun testAlignPages_emptyDocuments_handlesGracefully() {
        val emptyList1 = emptyList<String>()
        val emptyList2 = emptyList<String>()
        val result = PdfDiffEngine.alignPages(emptyList1, emptyList2)
        assertTrue(result.isEmpty())

        val onePage = listOf("Single page test")
        val resultOne = PdfDiffEngine.alignPages(onePage, emptyList2)
        assertEquals(1, resultOne.size)
        assertEquals(0, resultOne[0].doc1PageIndex)
        assertNull(resultOne[0].doc2PageIndex)
    }

    @Test fun rejectsQuadraticAlignmentAboveTheMemoryBudget() {
        val pages = java.util.Collections.nCopies(1000, "page")
        assertThrows(IllegalArgumentException::class.java) { PdfDiffEngine.alignPages(pages, pages) }
    }

    @Test fun rejectsAnUnboundedOneSidedPageList() {
        val pages = java.util.Collections.nCopies(com.pdfchemy.app.security.SecurityLimits.MAX_PAGE_COUNT + 1, "")
        assertThrows(IllegalArgumentException::class.java) { PdfDiffEngine.alignPages(pages, emptyList()) }
    }
    private fun raster(
        w1: Int, h1: Int, w2: Int, h2: Int,
        first: (Int, Int) -> Int = { _, _ -> -1 },
        second: (Int, Int) -> Int = { _, _ -> -1 },
        checkCancellation: () -> Unit = {}
    ): Pair<Float, List<IntArray>> {
        val rows = mutableListOf<IntArray>()
        val percent = PdfDiffEngine.compareRasterRows(w1, h1, w2, h2,
            { y, row -> for (x in row.indices) row[x] = first(x, y) },
            { y, row -> for (x in row.indices) row[x] = second(x, y) },
            { _, row -> rows.add(row.copyOf()) }, checkCancellation)
        return percent to rows
    }

    @Test fun equalRastersHaveNoDifferences() {
        assertEquals(0f, raster(2, 2, 2, 2).first, 0f)
    }

    @Test fun detectsAddedWidthAndRemovedHeightInUnion() {
        val (percent, rows) = raster(2, 3, 3, 2)
        assertEquals(50f, percent, 0f) // intersection 4, union 8, changed 4
        assertEquals(3, rows.size)
        assertEquals(3, rows[0].size)
        assertEquals(0xA02E7D32.toInt(), rows[0][2])
        assertEquals(0xA0C62828.toInt(), rows[2][0])
        assertEquals(0, rows[2][2]) // outside both pages
    }

    @Test fun detectsSinglePixelAndDoesNotRoundItToIdentical() {
        val result = raster(100, 100, 100, 100, second = { x, y ->
            if (x == 99 && y == 99) 0xff000000.toInt() else -1
        })
        assertEquals(0.01f, result.first, 0.00001f)
        assertEquals(0xE6EB1E64.toInt(), result.second.last().last())
    }

    @Test fun rasterThresholdRemainsThirtyPerChannel() {
        assertEquals(0f, raster(1, 1, 1, 1, second = { _, _ -> 0xffe1ffff.toInt() }).first, 0f)
        assertEquals(100f, raster(1, 1, 1, 1, second = { _, _ -> 0xffe0ffff.toInt() }).first, 0f)
    }

    @Test fun rejectsMissingRenderForAnExistingPage() {
        assertThrows(IllegalStateException::class.java) {
            PdfDiffEngine.requireRenderedPage<String>(2, null)
        }
        assertEquals("rendered", PdfDiffEngine.requireRenderedPage(2, "rendered"))
    }

    @Test fun cancellationPropagatesInsteadOfBecomingZeroDifference() {
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            raster(2, 2, 2, 2, checkCancellation = { throw java.util.concurrent.CancellationException() })
        }
    }

    @Test fun rejectsRasterAboveSecurityQuota() {
        assertThrows(IllegalArgumentException::class.java) { raster(2049, 1, 1, 1) }
    }

    @Test fun textDifferenceAfterSixHundredLinesIsNotLost() {
        val prefix = List(700) { "unchanged line $it" }
        val (added, removed) = PdfDiffEngine.computeLineDiff(prefix + "before", prefix + "after")
        assertEquals(listOf("after"), added)
        assertEquals(listOf("before"), removed)
    }

    @Test fun oversizedChangedTextFailsExplicitlyInsteadOfTruncating() {
        val left = List(1000) { "before $it" }
        val right = List(1000) { "after $it" }
        assertThrows(IllegalArgumentException::class.java) {
            PdfDiffEngine.computeLineDiff(left, right)
        }
    }
}
