package com.pdfchemy.app.ui

import android.net.Uri
import com.pdfchemy.app.logic.OutlineBookmark
import com.pdfchemy.app.logic.ReaderBackAction
import com.pdfchemy.app.logic.ReaderControlsUiState
import com.pdfchemy.app.logic.ReaderStartupCoordinator
import com.pdfchemy.app.logic.VanguardThreatResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test
    fun documentActionsRemainDisabledUntilSequentialStartupCompletes() {
        assertFalse(
            ReaderControlsUiState.canExecuteDocumentAction(
                isDocumentLoading = true,
                isVanguardScanning = false,
                totalPages = 1
            )
        )
        assertFalse(
            ReaderControlsUiState.canExecuteDocumentAction(
                isDocumentLoading = false,
                isVanguardScanning = true,
                totalPages = 1
            )
        )
        assertFalse(
            ReaderControlsUiState.canExecuteDocumentAction(
                isDocumentLoading = false,
                isVanguardScanning = false,
                totalPages = 0
            )
        )
        assertTrue(
            ReaderControlsUiState.canExecuteDocumentAction(
                isDocumentLoading = false,
                isVanguardScanning = false,
                totalPages = 1
            )
        )
    }

    @Test
    fun sequentialStartupFinishesAuditPageCountAndOutlineBeforePublishingReadyState() = runBlocking {
        val singleFlightWorkerBusy = AtomicBoolean(false)
        val events = mutableListOf<String>()

        val result = ReaderStartupCoordinator.executeSequentialStartup(
            isVanguardEnabled = true,
            onVanguardScanningChanged = { scanning ->
                events.add("scanning:$scanning")
            },
            checkVanguardThreat = {
                assertTrue("Single-flight worker must be idle on audit entry", singleFlightWorkerBusy.compareAndSet(false, true))
                events.add("audit")
                singleFlightWorkerBusy.set(false)
                VanguardThreatResult.Clean
            },
            getPageCount = {
                assertTrue("Single-flight worker must be idle on pageCount entry", singleFlightWorkerBusy.compareAndSet(false, true))
                events.add("pageCount")
                singleFlightWorkerBusy.set(false)
                1
            },
            extractOutline = {
                assertTrue("Single-flight worker must be idle on outline entry", singleFlightWorkerBusy.compareAndSet(false, true))
                events.add("outline")
                singleFlightWorkerBusy.set(false)
                listOf(OutlineBookmark(title = "Intro", pageNumber = 1))
            },
            onRecordHistory = {
                events.add("history")
            }
        )

        assertFalse("Single-flight worker must be idle when startup returns", singleFlightWorkerBusy.get())
        assertEquals(1, result.totalPages)
        assertEquals(1, result.bookmarks.size)
        assertEquals(
            listOf("scanning:true", "audit", "pageCount", "scanning:false", "history", "outline"),
            events
        )
    }

    @Test
    fun sequentialStartupPropagatesCancellationFromOutlineExtraction() = runBlocking {
        try {
            ReaderStartupCoordinator.executeSequentialStartup(
                isVanguardEnabled = false,
                onVanguardScanningChanged = {},
                checkVanguardThreat = { VanguardThreatResult.Clean },
                getPageCount = { 1 },
                extractOutline = { throw CancellationException("Reader left composition") },
                onRecordHistory = {}
            )
            fail("Expected CancellationException to be rethrown")
        } catch (_: CancellationException) {
            // Expected
        }
    }
}

