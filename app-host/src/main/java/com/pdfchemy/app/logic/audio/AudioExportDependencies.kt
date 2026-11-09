package com.pdfchemy.app.logic.audio

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.security.FreshOutputRegistry
import com.pdfchemy.app.security.PublicationCoordinator
import java.io.File
import java.util.UUID

object AudioExportDependencies {
    val jobStore = AudioExportJobStore()
    lateinit var stagingManager: AudioStagingManager
    private class Destination(val uri: Uri, val owner: Any, var publishing: Boolean = false)
    private val destinations = mutableMapOf<UUID, Destination>()

    @Synchronized fun registerDestination(jobId: UUID, uri: Uri, isFreshCopy: Boolean = OutputPolicy.isFreshCopy(uri)) {
        require(uri.scheme == "content") { "Audio destination must be a content URI" }
        check(jobId !in destinations) { "Audio job already has a destination" }
        if (!isFreshCopy) throw UnsupportedOperationException("Audio export requires a newly created output copy")
        val owner = Any()
        FreshOutputRegistry.claimKnown(listOf(uri), owner)
        FreshOutputRegistry.requireSafeSafTargets(listOf(uri), owner)
        destinations[jobId] = Destination(uri, owner)
    }
    @Synchronized private fun registered(jobId: UUID): Destination =
        requireNotNull(destinations[jobId]) { "Export destination no longer available" }
    fun destination(jobId: UUID): Uri = registered(jobId).uri
    fun isFreshDestination(jobId: UUID): Boolean = OutputPolicy.isFreshCopy(destination(jobId))

    internal suspend fun publishDestination(context: Context, jobId: UUID, wav: File, onPublished: (Uri) -> Unit) {
        val destination = synchronized(this) {
            registered(jobId).also {
                check(!it.publishing) { "Audio publisher is already active" }
                it.publishing = true
            }
        }
        try {
            AudioOutputPublisher.publishClaimed(context, wav, destination.uri, destination.owner, onPublished)
        } finally {
            synchronized(this) { destination.publishing = false }
        }
    }

    /** Call on IO after the publisher has closed its streams and released its writer lease. */
    fun forgetDestination(context: Context, jobId: UUID) {
        val destination = synchronized(this) {
            val current = destinations[jobId] ?: return
            check(!current.publishing && !PublicationCoordinator.hasActiveWriter(current.uri)) { "Audio publisher is still active" }
            destinations.remove(jobId)!!
        }
        FreshOutputRegistry.abandon(context, listOf(destination.uri), destination.owner)
    }

    @Synchronized fun initialize(context: Context) {
        if (!::stagingManager.isInitialized) {
            stagingManager = AudioStagingManager(context.applicationContext)
            // In-memory jobs cannot survive process death. Remove abandoned stages on first initialization.
            stagingManager.sweepStaleJobs(emptySet())
        }
    }
}
