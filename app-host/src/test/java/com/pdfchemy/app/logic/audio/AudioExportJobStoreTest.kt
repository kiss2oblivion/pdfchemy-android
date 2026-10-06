package com.pdfchemy.app.logic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class AudioExportJobStoreTest {

    private lateinit var jobStore: AudioExportJobStore

    @Before
    fun setup() {
        jobStore = AudioExportJobStore()
    }

    @Test
    fun `createJob initializes job in QUEUED state`() {
        val jobId = UUID.randomUUID()
        val job = jobStore.createJob(jobId)

        assertEquals(jobId, job.jobId)
        assertEquals(AudioExportState.QUEUED, job.state)
        assertEquals(0, job.processedCharacters)
        assertEquals(0, job.totalCharacters)
    }

    @Test
    fun `updateState modifies state and error`() {
        val jobId = UUID.randomUUID()
        jobStore.createJob(jobId)

        val error = RuntimeException("Test Error")
        jobStore.updateState(jobId, AudioExportState.FAILED, error)

        val updatedJob = jobStore.getJob(jobId)
        assertNotNull(updatedJob)
        assertEquals(AudioExportState.FAILED, updatedJob!!.state)
        assertEquals(error, updatedJob.error)
    }

    @Test
    fun `updateProgress updates metrics correctly`() {
        val jobId = UUID.randomUUID()
        jobStore.createJob(jobId)

        jobStore.updateProgress(jobId, processedCharacters = 1500, totalCharacters = 5000, projectedSizeBytes = 1024L * 1024L)

        val updatedJob = jobStore.getJob(jobId)
        assertNotNull(updatedJob)
        assertEquals(1500, updatedJob!!.processedCharacters)
        assertEquals(5000, updatedJob.totalCharacters)
        assertEquals(1048576L, updatedJob.projectedSizeBytes)
    }

    @Test
    fun `removeJob deletes job from store`() {
        val jobId = UUID.randomUUID()
        jobStore.createJob(jobId)
        assertNotNull(jobStore.getJob(jobId))

        jobStore.removeJob(jobId)
        assertNull(jobStore.getJob(jobId))
    }
}
