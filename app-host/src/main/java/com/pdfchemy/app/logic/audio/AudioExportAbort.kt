package com.pdfchemy.app.logic.audio

import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Shared terminal-abort path used by cancellation, service timeout and service destruction. */
class AudioExportAbort(
    private val id: UUID,
    private val store: AudioExportJobStore,
    private val invalidate: (Throwable) -> Unit,
    private val stopTts: () -> Unit,
    private val cancelPipeline: () -> Unit
) {
    private var aborted = false
    @Synchronized fun cancel() = abort(false, CancellationException("Audio export cancelled"))
    @Synchronized fun timeout() = abort(true, IllegalStateException("Media-processing service timed out"))
    @Synchronized fun interrupted() = abort(true, IllegalStateException("Audio export service interrupted"))
    private fun abort(failed: Boolean, error: Throwable) {
        if (aborted || store.getJob(id)?.state in AudioExportJobStore.terminalStates) return
        aborted = true
        store.updateState(id, if (failed) AudioExportState.FAILED else AudioExportState.CANCELLING, if (failed) error else null)
        invalidate(error)
        runCatching { stopTts() }
        cancelPipeline()
    }
}
