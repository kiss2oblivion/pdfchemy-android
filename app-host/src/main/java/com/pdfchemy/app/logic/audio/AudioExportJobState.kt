package com.pdfchemy.app.logic.audio

import java.util.UUID

enum class AudioExportState {
    QUEUED,
    INITIALIZING,
    SYNTHESIZING,
    ASSEMBLING,
    PUBLISHING,
    COMPLETED,
    CANCELLING,
    CANCELLED,
    FAILED
}

/** Immutable evidence of a completed output, independent of later job cancellation or failure. */
data class AudioPublicationReceipt(val outputUri: String)

data class AudioExportJob(
    val jobId: UUID,
    val state: AudioExportState,
    val totalCharacters: Int = 0,
    val processedCharacters: Int = 0,
    val projectedSizeBytes: Long = 0L,
    val error: Throwable? = null,
    val publication: AudioPublicationReceipt? = null
) {
    val outputUri: String? get() = publication?.outputUri
}
