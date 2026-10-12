package com.pdfchemy.app.logic.audio

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.logic.OutputPolicy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AudioExportDependencies {
    val jobStore = AudioExportJobStore()
    lateinit var stagingManager: AudioStagingManager
    private val destinations = ConcurrentHashMap<UUID, Uri>()
    private val destinationProvenance = ConcurrentHashMap<UUID, Boolean>()

    fun registerDestination(jobId: UUID, uri: Uri, isFreshCopy: Boolean = OutputPolicy.isFreshCopy(uri)) {
        require(uri.scheme == "content") { "Audio destination must be a content URI" }
        check(destinations.putIfAbsent(jobId, uri) == null)
        destinationProvenance[jobId] = isFreshCopy
    }
    fun destination(jobId: UUID): Uri = requireNotNull(destinations[jobId]) { "Export destination no longer available" }
    fun isFreshDestination(jobId: UUID): Boolean = destinationProvenance[jobId] ?: OutputPolicy.isFreshCopy(destination(jobId))
    fun forgetDestination(jobId: UUID) {
        destinations.remove(jobId)
        destinationProvenance.remove(jobId)
    }

    @Synchronized fun initialize(context: Context) {
        if (!::stagingManager.isInitialized) {
            stagingManager = AudioStagingManager(context.applicationContext)
            // In-memory jobs cannot survive process death. Remove abandoned stages on first initialization.
            stagingManager.sweepStaleJobs(emptySet())
        }
    }
}
