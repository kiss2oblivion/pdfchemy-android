package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AudioExportJobStore {
    private val _jobs = ConcurrentHashMap<UUID, MutableStateFlow<AudioExportJob>>()

    fun getJobFlow(jobId: UUID): StateFlow<AudioExportJob>? {
        return _jobs[jobId]?.asStateFlow()
    }

    fun getJob(jobId: UUID): AudioExportJob? {
        return _jobs[jobId]?.value
    }

    fun createJob(jobId: UUID): AudioExportJob {
        val initialJob = AudioExportJob(jobId = jobId, state = AudioExportState.QUEUED)
        _jobs.putIfAbsent(jobId, MutableStateFlow(initialJob))
        return _jobs[jobId]!!.value
    }

    fun updateState(jobId: UUID, state: AudioExportState, error: Throwable? = null) {
        _jobs[jobId]?.update {
            it.copy(state = state, error = error ?: it.error)
        }
    }

    fun updateProgress(jobId: UUID, processedCharacters: Int, totalCharacters: Int? = null, projectedSizeBytes: Long? = null) {
        _jobs[jobId]?.update {
            it.copy(
                processedCharacters = processedCharacters,
                totalCharacters = totalCharacters ?: it.totalCharacters,
                projectedSizeBytes = projectedSizeBytes ?: it.projectedSizeBytes
            )
        }
    }

    fun removeJob(jobId: UUID) {
        _jobs.remove(jobId)
    }

    fun getAllJobs(): List<AudioExportJob> {
        return _jobs.values.map { it.value }
    }
}
