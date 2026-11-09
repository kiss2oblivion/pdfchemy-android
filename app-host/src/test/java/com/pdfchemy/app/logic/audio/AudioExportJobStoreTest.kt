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

    private fun publishingJob(): UUID {
        val id = UUID.randomUUID()
        jobStore.createJob(id)
        for (state in listOf(AudioExportState.INITIALIZING, AudioExportState.SYNTHESIZING,
            AudioExportState.ASSEMBLING, AudioExportState.PUBLISHING)) {
            org.junit.Assert.assertTrue(jobStore.updateState(id, state))
        }
        return id
    }

    @Test fun `publication receipt survives cancellation without resurrecting job`() {
        val id = publishingJob()
        org.junit.Assert.assertTrue(jobStore.recordPublication(id, "content://fixture/saved.wav"))
        org.junit.Assert.assertTrue(jobStore.updateState(id, AudioExportState.CANCELLING))
        org.junit.Assert.assertTrue(jobStore.updateState(id, AudioExportState.CANCELLED))
        assertEquals(AudioExportState.CANCELLED, jobStore.getJob(id)!!.state)
        assertEquals("content://fixture/saved.wav", jobStore.getJob(id)!!.outputUri)
        org.junit.Assert.assertFalse(jobStore.updateState(id, AudioExportState.COMPLETED))
    }

    @Test fun `publication receipt survives later failure`() {
        val id = publishingJob()
        jobStore.recordPublication(id, "content://fixture/saved.wav")
        val error = IllegalStateException("Synthetic result delivery failure")
        jobStore.updateState(id, AudioExportState.FAILED, error)
        assertEquals(AudioExportState.FAILED, jobStore.getJob(id)!!.state)
        assertEquals(error, jobStore.getJob(id)!!.error)
        assertEquals("content://fixture/saved.wav", jobStore.getJob(id)!!.outputUri)
    }

    @Test fun `commit receipt is retained when abort races its callback`() {
        for (abortState in listOf(AudioExportState.CANCELLING, AudioExportState.FAILED)) {
            val id = publishingJob()
            jobStore.updateState(id, abortState)
            org.junit.Assert.assertTrue(jobStore.recordPublication(id, "content://fixture/$id.wav"))
            assertEquals(abortState, jobStore.getJob(id)!!.state)
            assertEquals("content://fixture/$id.wav", jobStore.getJob(id)!!.outputUri)
        }
    }

    @Test fun `publication receipt cannot be replaced by another output`() {
        val id = publishingJob()
        org.junit.Assert.assertTrue(jobStore.recordPublication(id, "content://fixture/saved.wav"))
        org.junit.Assert.assertTrue(jobStore.recordPublication(id, "content://fixture/saved.wav"))
        org.junit.Assert.assertFalse(jobStore.recordPublication(id, "content://fixture/other.wav"))
        org.junit.Assert.assertFalse(jobStore.updateState(id, AudioExportState.COMPLETED, outputUri = "content://fixture/other.wav"))
        assertEquals("content://fixture/saved.wav", jobStore.getJob(id)!!.outputUri)
        org.junit.Assert.assertTrue(jobStore.updateState(id, AudioExportState.COMPLETED, outputUri = "content://fixture/saved.wav"))
    }

    @Test fun `unstarted and fully cancelled jobs reject new receipts`() {
        val id = UUID.randomUUID()
        jobStore.createJob(id)
        org.junit.Assert.assertFalse(jobStore.recordPublication(id, "content://fixture/unstarted.wav"))
        jobStore.updateState(id, AudioExportState.CANCELLING)
        jobStore.updateState(id, AudioExportState.CANCELLED)
        org.junit.Assert.assertFalse(jobStore.recordPublication(id, "content://fixture/late.wav"))
        assertNull(jobStore.getJob(id)!!.outputUri)
    }
}
