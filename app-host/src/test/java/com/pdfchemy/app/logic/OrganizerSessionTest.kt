package com.pdfchemy.app.logic

import org.junit.Assert.*
import org.junit.Test

class OrganizerSessionTest {

    @Test
    fun testInitializationPreservesAllPages() {
        val session = OrganizerSession()
        session.initialize(5)

        assertEquals(5, session.pages.size)
        assertEquals(0, session.selectedIndex)

        for (i in 0 until 5) {
            val page = session.pages[i]
            assertEquals(i, page.originalIndex)
            assertEquals(0, page.rotation)
            assertFalse(page.isBlank)
            assertEquals(PageThumbnailState.LOADING, page.previewState)
        }
    }

    @Test
    fun testThumbnailFailureDoesNotDropPage() {
        val session = OrganizerSession()
        session.initialize(3)

        // Page 0 ready, Page 1 failed, Page 2 ready
        session.updateThumbnail(0, null, PageThumbnailState.READY)
        session.updateThumbnail(1, null, PageThumbnailState.FAILED)
        session.updateThumbnail(2, null, PageThumbnailState.READY)

        assertEquals(3, session.pages.size)
        assertEquals(PageThumbnailState.FAILED, session.pages[1].previewState)

        val actions = session.toPageActions()
        assertEquals("Export actions must contain all 3 pages even if thumbnail failed", 3, actions.size)
        assertEquals(0, actions[0].originalPageIndex)
        assertEquals(1, actions[1].originalPageIndex)
        assertEquals(2, actions[2].originalPageIndex)
    }

    @Test
    fun testChronologicalUndoAndRedo() {
        val session = OrganizerSession()
        session.initialize(3)

        assertFalse(session.canUndo())
        assertFalse(session.canRedo())

        // 1. Delete page at index 0 (leaves 2 pages: orig 1, 2)
        session.selectPage(0)
        assertTrue(session.deleteSelected())
        assertEquals(2, session.pages.size)
        assertEquals(1, session.pages[0].originalIndex)
        assertTrue(session.canUndo())
        assertFalse(session.canRedo())

        // 2. Rotate current selected page
        session.rotateSelected(90)
        assertEquals(90, session.pages[0].rotation)

        // 3. Undo rotate
        assertTrue(session.undo())
        assertEquals(0, session.pages[0].rotation)
        assertTrue(session.canRedo())

        // 4. Undo delete (restores original 3 pages)
        assertTrue(session.undo())
        assertEquals(3, session.pages.size)
        assertEquals(0, session.pages[0].originalIndex)
        assertFalse(session.canUndo())
        assertTrue(session.canRedo())

        // 5. Redo delete
        assertTrue(session.redo())
        assertEquals(2, session.pages.size)
        assertEquals(1, session.pages[0].originalIndex)

        // 6. Redo rotate
        assertTrue(session.redo())
        assertEquals(90, session.pages[0].rotation)
        assertFalse(session.canRedo())
    }

    @Test
    fun testNewActionClearsRedo() {
        val session = OrganizerSession()
        session.initialize(3)

        session.rotateAll(90)
        session.undo()
        assertTrue(session.canRedo())

        // Performing a new action must clear the forward redo stack
        session.insertBlank(0)
        assertFalse(session.canRedo())
    }

    @Test
    fun testDeleteLastPageBlocked() {
        val session = OrganizerSession()
        session.initialize(1)

        assertFalse("Cannot delete the only remaining page", session.deleteSelected())
        assertEquals(1, session.pages.size)
    }

    @Test
    fun testMoveDuplicateAndReverse() {
        val session = OrganizerSession()
        session.initialize(3) // 0, 1, 2

        // Move page 0 right -> order becomes 1, 0, 2
        session.selectPage(0)
        session.moveSelectedRight()
        assertEquals(1, session.pages[0].originalIndex)
        assertEquals(0, session.pages[1].originalIndex)
        assertEquals(2, session.pages[2].originalIndex)

        // Duplicate page at index 1 (orig 0) -> order becomes 1, 0, 0, 2
        session.selectPage(1)
        session.duplicateSelected()
        assertEquals(4, session.pages.size)
        assertEquals(0, session.pages[1].originalIndex)
        assertEquals(0, session.pages[2].originalIndex)

        // Reverse -> order becomes 2, 0, 0, 1
        session.reverse()
        assertEquals(2, session.pages[0].originalIndex)
        assertEquals(0, session.pages[1].originalIndex)
        assertEquals(0, session.pages[2].originalIndex)
        assertEquals(1, session.pages[3].originalIndex)

        // Undo reverse -> restores 1, 0, 0, 2
        session.undo()
        assertEquals(1, session.pages[0].originalIndex)
        assertEquals(0, session.pages[1].originalIndex)
    }

    @Test
    fun testResetRestoresBaseline() {
        val session = OrganizerSession()
        session.initialize(3)

        session.deleteSelected()
        session.rotateAll(180)
        session.insertBlank(0)

        assertTrue(session.canReset())
        session.reset()

        assertEquals(3, session.pages.size)
        assertEquals(0, session.pages[0].originalIndex)
        assertEquals(0, session.pages[0].rotation)
    }
}
