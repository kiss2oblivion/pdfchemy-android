package com.pdfchemy.app.logic

import com.pdfchemy.app.ui.ErrorPresentation
import org.junit.Assert.*
import org.junit.Test

class VisualWorkflowTest {
    @Test fun visualSelectionRoundTripsWithoutDuplicatesAndIgnoresInvalidBounds() {
        val pages = VisualPageRanges.parse("2-4,4,9,0,11,7-5,1-2-3", 10)
        assertEquals(setOf(2,3,4,9), pages)
        assertEquals(pages, VisualPageRanges.parse(VisualPageRanges.format(pages), 10))
    }
    @Test fun thousandPageUndoKeepsIdentitiesAndCapsRetainedHistory() {
        val session = OrganizerSession(); session.initialize(1000)
        val identities = session.pages.map { it.id }.toSet()
        repeat(75) { session.rotateSelected() }
        var undos = 0
        while (session.undo()) undos++
        assertEquals(50, undos)
        assertEquals(identities, session.pages.map { it.id }.toSet())
        assertEquals(1000, session.toPageActions().size)
        assertTrue(session.pages.all { it.thumbnail == null })
    }
    @Test fun providerAndParseFailuresNeverBecomeExecutableThreatCopy() {
        assertEquals(ErrorPresentation.Recovery.ACCESS, ErrorPresentation.classify("FileNotFoundException: revoked document"))
        assertEquals(ErrorPresentation.Recovery.DAMAGED, ErrorPresentation.classify("Invalid PDF header"))
        assertEquals(ErrorPresentation.Recovery.PASSWORD, ErrorPresentation.classify("InvalidPasswordException"))
        assertEquals(ErrorPresentation.Recovery.STORAGE, ErrorPresentation.classify("write failed ENOSPC"))
        assertEquals(ErrorPresentation.Recovery.OPERATION, ErrorPresentation.classify("worker disconnected"))
    }
    @Test fun activeBatchDoesNotClaimItsCurrentFileIsCompleted() {
        val active = com.pdfchemy.app.ui.MainViewModel.UiState.BatchProcessing(1,1,"document.pdf")
        assertEquals(0, active.completed)
        assertEquals(1, active.copy(completed=1).completed)
    }
}
