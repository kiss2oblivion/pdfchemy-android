package com.pdfchemy.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.audio.AudioExportDependencies
import com.pdfchemy.app.logic.audio.AudioExportState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** A saved result stays usable even when the job's result delivery was cancelled or failed. */
class AudioPublicationRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val id = UUID.randomUUID()
    private val jobs = AudioExportDependencies.jobStore
    private val viewModels = ViewModelStore()
    private val model = MainViewModel(app).also { viewModels.put("audio-recovery", it) }

    @After fun cleanup() {
        compose.runOnIdle {
            jobs.removeJob(id)
            viewModels.clear()
        }
    }

    private fun savedActionsRemainAvailable(end: AudioExportState) {
        jobs.createJob(id)
        for (phase in listOf(AudioExportState.INITIALIZING, AudioExportState.SYNTHESIZING,
            AudioExportState.ASSEMBLING, AudioExportState.PUBLISHING)) assertTrue(jobs.updateState(id, phase))
        compose.setContent { MaterialTheme { ReflowReaderScreen(viewModel = model, onBack = {}) } }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(app.getString(R.string.audio_export_title)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertTrue(jobs.recordPublication(id, "content://audio-recovery/saved.wav"))
            if (end == AudioExportState.CANCELLED) assertTrue(jobs.updateState(id, AudioExportState.CANCELLING))
            assertTrue(jobs.updateState(id, end))
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(app.getString(R.string.audio_export_saved)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.audio_export_complete)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.btn_open_file)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.share)).assertIsDisplayed()
    }

    @Test fun cancelledDeliveryStillShowsTheSavedFileActions() = savedActionsRemainAvailable(AudioExportState.CANCELLED)
    @Test fun failedDeliveryStillShowsTheSavedFileActions() = savedActionsRemainAvailable(AudioExportState.FAILED)
}
