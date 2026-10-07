package com.pdfchemy.app.logic.audio

import android.content.Context
import android.net.Uri
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AudioExportDependencies {
    val jobStore = AudioExportJobStore()
    lateinit var stagingManager: AudioStagingManager
    private val destinations = ConcurrentHashMap<UUID, Uri>()

    fun registerDestination(jobId: UUID, uri: Uri) {
        require(uri.scheme == "content") { "Audio destination must be a content URI" }
        check(destinations.putIfAbsent(jobId, uri) == null)
    }
    fun destination(jobId: UUID): Uri = requireNotNull(destinations[jobId]) { "Export destination no longer available" }
    fun forgetDestination(jobId: UUID) { destinations.remove(jobId) }

    @Synchronized fun initialize(context: Context) {
        if (!::stagingManager.isInitialized) {
            stagingManager = AudioStagingManager(context.applicationContext)
            // In-memory jobs cannot survive process death. Remove abandoned stages on first initialization.
            stagingManager.sweepStaleJobs(emptySet())
        }
    }
}
