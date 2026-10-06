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

    @Synchronized
    fun updateState(jobId: UUID, state: AudioExportState, error: Throwable? = null, outputUri: String? = null): Boolean {
        val flow = _jobs[jobId] ?: return false
        val current = flow.value
        if (state !in transitions(current.state)) return false
        flow.value = current.copy(state = state, error = error, outputUri = if (state == AudioExportState.COMPLETED) outputUri else null)
        return true
    }

    fun updateProgress(jobId: UUID, processedCharacters: Int, totalCharacters: Int? = null, projectedSizeBytes: Long? = null) {
        _jobs[jobId]?.update {
            if (it.state in terminalStates || it.state == AudioExportState.CANCELLING) return@update it
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

    companion object {
        val terminalStates = setOf(AudioExportState.COMPLETED, AudioExportState.CANCELLED, AudioExportState.FAILED)
        private fun transitions(state: AudioExportState): Set<AudioExportState> {
            if (state in terminalStates) return emptySet()
            if (state == AudioExportState.CANCELLING) return setOf(AudioExportState.CANCELLED, AudioExportState.FAILED)
            val next = when (state) {
                AudioExportState.QUEUED -> AudioExportState.INITIALIZING
                AudioExportState.INITIALIZING -> AudioExportState.SYNTHESIZING
                AudioExportState.SYNTHESIZING -> AudioExportState.ASSEMBLING
                AudioExportState.ASSEMBLING -> AudioExportState.PUBLISHING
                AudioExportState.PUBLISHING -> AudioExportState.COMPLETED
                else -> error("Invalid state")
            }
            return setOf(next, AudioExportState.CANCELLING, AudioExportState.FAILED)
        }
    }
}
