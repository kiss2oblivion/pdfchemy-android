package com.pdfchemy.app.logic

import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [])
class AnnotationParityTest {
    @Test fun savingKeepsVisibleAnnotationsAndUndoMakesTheCopyDirtyAgain() {
        val session = EditorSession()
        session.rotatePage(0)
        session.markSaved()
        assertFalse(session.isDirty)
        assertEquals(90, session.getModification(0).rotationDegrees)
        session.addTextAnnotation(0, TextAnnotation(text="kept",xRatio=.4f,yRatio=.6f))
        assertTrue(session.isDirty)
        session.undo()
        assertFalse(session.isDirty)
        session.clear()
        assertTrue(session.getModifications().isEmpty())
        assertFalse(session.canUndo)
    }

    @Test fun signaturePlacementHistoryTreatsDragAsOneChronologicalAction() {
        val history = SessionHistory(listOf<String>())
        history.replace(listOf("signature"))
        history.replace(listOf("signature moved")) // Committed gesture, rather than individual pointer events.
        history.replace(emptyList())
        assertEquals(listOf("signature moved"), history.undo())
        assertEquals(listOf("signature"), history.undo())
        assertEquals(listOf("signature moved"), history.redo())
        history.replace(listOf("another signature"))
        assertFalse(history.canRedo)
        history.reset(emptyList())
        assertFalse(history.canUndo)
    }
}
