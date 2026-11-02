package com.pdfchemy.app.logic

import org.junit.Assert.*
import org.junit.Test

/** Dirty state is relative to the last saved effective edits, including an empty current state. */
class EditorSavedStateRegressionTest {
    @Test fun undoSavedRotationIsDirtyAndRedoReturnsToSavedState() {
        val session = EditorSession()
        session.rotatePage(0)
        session.markSaved()
        assertFalse(session.isDirty)

        assertEquals(0, session.undo())
        assertEquals(0, session.getModification(0).rotationDegrees)
        assertTrue("Undo removed a saved rotation", session.isDirty)

        assertEquals(0, session.redo())
        assertEquals(90, session.getModification(0).rotationDegrees)
        assertFalse("Redo restored the saved state", session.isDirty)
    }

    @Test fun reversingSavedRotationIsDirtyUntilTheSavedRotationIsRestored() {
        val session = EditorSession()
        session.rotatePage(0)
        session.markSaved()

        session.rotatePage(0, -90)
        assertTrue("Zero rotation differs from the saved 90 degrees", session.isDirty)

        session.rotatePage(0)
        assertFalse("Returning to the saved rotation is clean", session.isDirty)
    }

    @Test fun removingLastSavedAnnotationIsDirtyAcrossUndoAndRedo() {
        val session = EditorSession()
        val annotation = TextAnnotation(text = "Saved note", xRatio = .2f, yRatio = .4f)
        session.addTextAnnotation(0, annotation)
        session.markSaved()

        session.removeTextAnnotation(0, annotation.id)
        assertTrue("Removing the last saved note is still an unsaved change", session.isDirty)
        session.undo()
        assertFalse("Undo restored the saved note", session.isDirty)
        session.redo()
        assertTrue(session.isDirty)
    }

    @Test fun restoringSavedDeletedPageIsDirtyUntilDeletionIsRestored() {
        val session = EditorSession()
        session.deletePage(2)
        session.markSaved()

        session.deletePage(2, false)
        assertTrue("Restoring a page changes the saved deletion", session.isDirty)
        session.undo()
        assertFalse(session.isDirty)
    }

    @Test fun revertedNewPageEditsDoNotMakeOtherSavedPageDirty() {
        val session = EditorSession()
        session.rotatePage(0)
        session.markSaved()
        val annotation = TextAnnotation(text = "Temporary note", xRatio = .3f, yRatio = .5f)

        session.addTextAnnotation(1, annotation)
        assertTrue(session.isDirty)
        session.removeTextAnnotation(1, annotation.id)
        assertFalse("An empty page entry has no effect on the saved PDF", session.isDirty)

        session.undo()
        assertTrue(session.isDirty)
        session.redo()
        assertFalse(session.isDirty)
    }

    @Test fun noOpEntriesInSavedAndCurrentMapsRemainClean() {
        val session = EditorSession()
        session.rotatePage(0, 360)
        session.markSaved()
        assertFalse(session.isDirty)

        session.deletePage(1, false)
        session.rotatePage(2, -360)
        assertFalse("Default entries on different pages are not effective edits", session.isDirty)
        session.reset()
        assertFalse(session.isDirty)
    }
}
