package com.pdfchemy.app.logic

import org.junit.Assert.*
import org.junit.Test

class ReaderNavigationTest {
    @Test fun searchCountsEveryOccurrenceAndRetainsParagraphAndCharacterLocation() {
        val sections = listOf(ReflowSection(1, null, listOf("Needle and NEEDLE", "none", "needle")),
            ReflowSection(3, null, listOf("needle again")))
        val hits = ReaderNavigation.occurrences(sections, "needle")
        assertEquals(4, hits.size)
        assertEquals(listOf(0, 11, 0, 0), hits.map { it.start })
        assertEquals(listOf(0, 0, 2, 0), hits.map { it.paragraph })
        assertEquals(listOf(0, 0, 0, 1), hits.map { it.section })
        assertTrue(ReaderNavigation.occurrences(sections, " ").isEmpty())
    }

    @Test fun nestedOutlinePreservesDuplicateTitlesAndDescendantPageTargets() {
        val nested = OutlineBookmark("Chapter", 2, listOf(OutlineBookmark("Chapter", 7)))
        val rows = ReaderNavigation.flattenOutline(listOf(nested, OutlineBookmark("End", 12)))
        assertEquals(listOf(2, 7, 12), rows.map { it.bookmark.pageNumber })
        assertEquals(listOf(0, 1, 0), rows.map { it.depth })
    }
}
