package com.pdfchemy.app.ui

import com.pdfchemy.app.logic.ReaderBackAction
import com.pdfchemy.app.logic.ReaderControlsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderControlsStateTest {

    @Test
    fun singleTapTogglesImmersiveReadingModeAndCollapsesTransientOverlays() {
        val initial = ReaderControlsUiState(showControls = true)
        val searching = initial.onToggleSearch()
        assertTrue(searching.showControls)
        assertTrue(searching.isSearchMode)

        // Single tap on canvas hides controls and collapses search bar for unobstructed reading
        val immersive = searching.onCanvasTap()
        assertFalse(immersive.showControls)
        assertFalse(immersive.isSearchMode)

        // Next single tap reveals controls cleanly without re-opening transient search bar
        val revealed = immersive.onCanvasTap()
        assertTrue(revealed.showControls)
        assertFalse(revealed.isSearchMode)
    }

    @Test
    fun backActionPriorityClosesSheetsAndSearchBeforeResettingZoomOrExiting() {
        val stateWithAll = ReaderControlsUiState(
            showControls = true,
            isSearchMode = true,
            showTocSheet = false,
            showCommandCentreSheet = true
        )

        val (action1, state1) = stateWithAll.resolveBackAction(isZoomed = true)
        assertEquals(ReaderBackAction.CLOSE_COMMAND_CENTRE, action1)
        assertFalse(state1.showCommandCentreSheet)
        assertTrue(state1.isSearchMode)

        val stateWithToc = state1.onOpenToc()
        val (action2, state2) = stateWithToc.resolveBackAction(isZoomed = true)
        assertEquals(ReaderBackAction.CLOSE_TOC, action2)
        assertFalse(state2.showTocSheet)

        val (action3, state3) = state2.resolveBackAction(isZoomed = true)
        assertEquals(ReaderBackAction.CLOSE_SEARCH, action3)
        assertFalse(state3.isSearchMode)

        val (action4, state4) = state3.resolveBackAction(isZoomed = true)
        assertEquals(ReaderBackAction.RESET_ZOOM, action4)

        val (action5, _) = state4.resolveBackAction(isZoomed = false)
        assertEquals(ReaderBackAction.EXIT_READER, action5)
    }

    @Test
    fun viewportPaddingRemainsConstantAcrossControlVisibilityTogglesToPreventScrollJump() {
        val portraitPadding = ReaderControlsUiState.computeStableViewportPadding(isLandscape = false)
        val landscapePadding = ReaderControlsUiState.computeStableViewportPadding(isLandscape = true)

        assertEquals(80, portraitPadding.topDp)
        assertEquals(120, portraitPadding.bottomDp)
        assertEquals(64, landscapePadding.topDp)
        assertEquals(96, landscapePadding.bottomDp)
    }
}

