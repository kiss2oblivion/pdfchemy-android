package com.pdfchemy.app.logic.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioStagingManagerTest {

    private lateinit var context: Context
    private lateinit var stagingManager: AudioStagingManager
    private lateinit var baseDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stagingManager = AudioStagingManager(context)
        baseDir = File(context.cacheDir, "audio-export")
    }

    @After
    fun tearDown() {
        if (baseDir.exists()) {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun createsJobDirectoriesAndFilesCorrectly() {
        val jobId = UUID.randomUUID()
        val jobDir = stagingManager.getJobDir(jobId)
        
        assertTrue(jobDir.exists())
        
        val tempChunk = stagingManager.getTempChunkFile(jobId, 0)
        val masterRaw = stagingManager.getMasterRawFile(jobId)
        val finalWav = stagingManager.getFinalWavFile(jobId)
        val sourceText = stagingManager.getTextArtifactFile(jobId)
        
        assertEquals("chunk_temp_0.wav", tempChunk.name)
        assertEquals("master.raw", masterRaw.name)
        assertEquals("master.wav", finalWav.name)
        assertEquals("source_text.txt", sourceText.name)
    }

    @Test
    fun clearJobStagingRemovesSpecificJobDirectory() {
        val jobId = UUID.randomUUID()
        val jobDir = stagingManager.getJobDir(jobId)
        
        File(jobDir, "test.txt").writeText("data")
        assertTrue(jobDir.exists())
        
        stagingManager.clearJobStaging(jobId)
        assertFalse(jobDir.exists())
    }

    @Test
    fun sweepStaleJobsRemovesInactiveJobsAndKeepsActiveOnes() {
        val activeJobId = UUID.randomUUID()
        val staleJobId1 = UUID.randomUUID()
        val staleJobId2 = UUID.randomUUID()

        val activeDir = stagingManager.getJobDir(activeJobId)
        val staleDir1 = stagingManager.getJobDir(staleJobId1)
        val staleDir2 = stagingManager.getJobDir(staleJobId2)

        assertTrue(activeDir.exists())
        assertTrue(staleDir1.exists())
        assertTrue(staleDir2.exists())

        stagingManager.sweepStaleJobs(setOf(activeJobId))

        assertTrue(activeDir.exists())
        assertFalse(staleDir1.exists())
        assertFalse(staleDir2.exists())
    }
}
