package com.pdfchemy.app.logic

import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditorSessionTest {

    @Test
    fun `initial state is clean and cannot undo or redo`() {
        val session = EditorSession()
        assertFalse(session.canUndo)
        assertFalse(session.canRedo)
        assertFalse(session.isDirty)
        assertTrue(session.getModifications().isEmpty())
    }

    @Test
    fun `adding drawing updates dirty state and supports undo redo`() {
        val session = EditorSession()
        val path = DrawingPath(
            points = listOf(DrawingPoint(0.1f, 0.1f), DrawingPoint(0.2f, 0.2f)),
            color = Color.BLACK,
            strokeWidth = 4f
        )
        session.addDrawing(0, path)

        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        assertTrue(session.isDirty)
        assertEquals(1, session.getModification(0).drawings.size)

        val affectedPage = session.undo()
        assertEquals(0, affectedPage)
        assertFalse(session.canUndo)
        assertTrue(session.canRedo)
        assertFalse(session.isDirty)
        assertEquals(0, session.getModification(0).drawings.size)

        val redonePage = session.redo()
        assertEquals(0, redonePage)
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        assertTrue(session.isDirty)
        assertEquals(1, session.getModification(0).drawings.size)
    }

    @Test
    fun `chronological order is preserved across mixed operations`() {
        val session = EditorSession()

        // 1. Drawing
        session.addDrawing(
            0,
            DrawingPath(
                points = listOf(DrawingPoint(0f, 0f), DrawingPoint(0.5f, 0.5f)),
                color = Color.RED,
                strokeWidth = 2f
            )
        )

        // 2. Rotate
        session.rotatePage(0, 90)

        // 3. Text
        session.addTextAnnotation(
            0,
            TextAnnotation(text = "Hello World", xRatio = 0.3f, yRatio = 0.4f)
        )

        // 4. Stamp
        session.addStamp(
            0,
            StampAnnotation(type = StampType.APPROVED, xRatio = 0.5f, yRatio = 0.5f)
        )

        // 5. Redaction
        session.addRedaction(
            0,
            RedactionBox(pageIndex = 0, normalizedRect = RectF(0.1f, 0.1f, 0.4f, 0.2f))
        )

        // Verify current state has all 5
        val mod5 = session.getModification(0)
        assertEquals(1, mod5.drawings.size)
        assertEquals(90, mod5.rotationDegrees)
        assertEquals(1, mod5.textAnnotations.size)
        assertEquals(1, mod5.stamps.size)
        assertEquals(1, mod5.redactions.size)

        // Undo 1: Redaction is removed, others remain
        session.undo()
        val mod4 = session.getModification(0)
        assertEquals(0, mod4.redactions.size)
        assertEquals(1, mod4.stamps.size)
        assertEquals(1, mod4.textAnnotations.size)
        assertEquals(90, mod4.rotationDegrees)
        assertEquals(1, mod4.drawings.size)

        // Undo 2: Stamp is removed
        session.undo()
        val mod3 = session.getModification(0)
        assertEquals(0, mod3.stamps.size)
        assertEquals(1, mod3.textAnnotations.size)
        assertEquals(90, mod3.rotationDegrees)
        assertEquals(1, mod3.drawings.size)

        // Undo 3: Text is removed
        session.undo()
        val mod2 = session.getModification(0)
        assertEquals(0, mod2.textAnnotations.size)
        assertEquals(90, mod2.rotationDegrees)
        assertEquals(1, mod2.drawings.size)

        // Undo 4: Rotation is reverted to 0
        session.undo()
        val mod1 = session.getModification(0)
        assertEquals(0, mod1.rotationDegrees)
        assertEquals(1, mod1.drawings.size)

        // Undo 5: Drawing is removed
        session.undo()
        val mod0 = session.getModification(0)
        assertEquals(0, mod0.drawings.size)
        assertFalse(session.isDirty)
        assertFalse(session.canUndo)
        assertTrue(session.canRedo)
    }

    @Test
    fun `new action after undo invalidates forward redo history`() {
        val session = EditorSession()
        session.rotatePage(0, 90)
        session.rotatePage(0, 90) // now 180

        session.undo() // undid to 90
        assertTrue(session.canRedo)

        // New action: add text
        session.addTextAnnotation(0, TextAnnotation(text = "Divergent", xRatio = 0.1f, yRatio = 0.1f))

        // Redo stack must be cleared
        assertFalse(session.canRedo)
        assertTrue(session.canUndo)
        assertEquals(90, session.getModification(0).rotationDegrees)
        assertEquals(1, session.getModification(0).textAnnotations.size)
    }
}
