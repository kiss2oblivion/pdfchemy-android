package com.pdfchemy.app.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.Screen
import com.pdfchemy.app.logic.ReaderCommandAction
import com.pdfchemy.app.logic.ReaderCommandCentreCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderCommandCentreTest {

    @Test
    fun allCorePdfOperationsAreReachableWithinOneOrTwoTapsFromReader() {
        val actions = ReaderCommandCentreCatalog.allSheetActions
        assertEquals(17, actions.size)
        for (action in actions) {
            assertTrue(
                "Action $action must be reachable in 1 or 2 taps from Reader, was ${action.maxTapsFromReader}",
                action.maxTapsFromReader in 1..2
            )
        }
        assertTrue(ReaderCommandCentreCatalog.primaryBarActions.all { it.maxTapsFromReader == 1 })
    }

    @Test
    fun everyCommandCentreActionPreservesDocumentContextAndReturnsToReaderOnBack() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(app)
        val activePdf = Uri.parse("file:///data/user/0/com.pdfchemy.app/cache/staged_documents/snapshot_cmd.pdf")

        for (action in ReaderCommandCentreCatalog.allSheetActions) {
            viewModel.readerNavigation.onEnterReader(activePdf)
            val targetScreen = ReaderCommandCentreCatalog.resolveScreen(action, activePdf)
            val navigated = viewModel.navigateFromReaderToTool(activePdf, targetScreen)
            assertEquals(targetScreen, navigated)

            when (navigated) {
                is Screen.PdfEditor -> assertEquals(activePdf, navigated.initialPdfUri)
                is Screen.ReflowReader -> assertEquals(activePdf, navigated.initialUri)
                else -> assertEquals(
                    "Action $action must publish continuityDocumentUri for seamless pre-selection",
                    activePdf,
                    viewModel.continuityDocumentUri.value
                )
            }

            // Simulate tool consuming continuity URI and user pressing Back
            viewModel.clearContinuityUri()
            val returnScreen = viewModel.returnFromTool(Screen.Home)
            assertEquals(
                "Pressing Back from $action must return to PdfReader with active document preserved",
                Screen.PdfReader(activePdf),
                returnScreen
            )
        }
    }
}
