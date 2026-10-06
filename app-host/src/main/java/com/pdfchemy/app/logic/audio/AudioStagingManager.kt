package com.pdfchemy.app.logic.audio

import android.content.Context
import java.io.File
import java.util.UUID

class AudioStagingManager(private val context: Context) {

    private fun getBaseDir(): File {
        val dir = File(context.cacheDir, "audio-export")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getJobDir(jobId: UUID): File {
        val dir = File(getBaseDir(), jobId.toString())
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getTempChunkFile(jobId: UUID, chunkIndex: Int): File {
        return File(getJobDir(jobId), "chunk_temp_${chunkIndex}.wav")
    }

    fun getMasterRawFile(jobId: UUID): File {
        return File(getJobDir(jobId), "master.raw")
    }

    fun getFinalWavFile(jobId: UUID): File {
        return File(getJobDir(jobId), "master.wav")
    }

    fun getTextArtifactFile(jobId: UUID): File {
        return File(getJobDir(jobId), "source_text.txt")
    }

    fun clearJobStaging(jobId: UUID) {
        val dir = getJobDir(jobId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    fun sweepStaleJobs(activeJobIds: Set<UUID>) {
        val baseDir = getBaseDir()
        val activeIdsStr = activeJobIds.map { it.toString() }.toSet()
        
        baseDir.listFiles()?.forEach { file ->
            if (file.isDirectory && !activeIdsStr.contains(file.name)) {
                file.deleteRecursively()
            }
        }
    }
}
