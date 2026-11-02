package com.pdfchemy.app.logic

import org.junit.Assert.*
import org.junit.Test

class OcrProgressTrackerTest {
    @Test fun recognitionFinishesBeforeSavingAndLateEventsCannotPublish() {
        val tracker = OcrProgressTracker(7,500)
        assertEquals(OcrProgress(0,2,false), tracker.accept(7,0,2,false))
        tracker.accept(7,1,2,false); tracker.accept(7,2,2,false)
        assertEquals(OcrProgress(2,2,true), tracker.accept(7,2,2,true))
        tracker.close()
        assertNull(tracker.accept(7,2,2,true))
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected invalid progress rejection") } catch (_: IllegalArgumentException) {} }
    @Test fun foreignTokenAndOutOfQuotaPayloadCannotStartProgress() {
        val tracker = OcrProgressTracker(7,500)
        rejects { tracker.accept(8,0,2,false) }
        rejects { tracker.accept(7,0,501,false) }
        rejects { tracker.accept(7,-1,2,false) }
        rejects { tracker.accept(7,1,2,false) }
        assertNotNull(tracker.accept(7,0,2,false))
    }
    @Test fun duplicateSkippedRegressingAndChangingTotalsCannotAdvance() {
        val tracker = OcrProgressTracker(7,500); tracker.accept(7,0,3,false)
        rejects { tracker.accept(7,0,3,false) }
        rejects { tracker.accept(7,2,3,false) }
        rejects { tracker.accept(7,1,4,false) }
        rejects { tracker.accept(7,3,3,true) }
        tracker.accept(7,1,3,false)
        rejects { tracker.accept(7,0,3,false) }
        assertEquals(OcrProgress(2,3,false), tracker.accept(7,2,3,false))
    }
}
