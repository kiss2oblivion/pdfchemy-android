package com.pdfchemy.app.logic.audio

import android.content.Context
import java.io.File
import java.util.UUID

class AudioStagingManager(private val cacheDir: File) {
    constructor(context: Context) : this(context.applicationContext.cacheDir)

    private val stagingLock = Any()

    private fun getBaseDir(): File {
        val dir = File(cacheDir, "audio-export")
        if (!dir.exists()) {
            check(dir.mkdirs()) { "Unable to create audio staging" }
        }
        return dir
    }

    fun getJobDir(jobId: UUID): File {
        val dir = File(getBaseDir(), jobId.toString())
        if (!dir.exists()) {
            check(dir.mkdirs()) { "Unable to create job staging" }
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

    fun getFinalM4aFile(jobId: UUID): File {
        return File(getJobDir(jobId), "master.m4a")
    }

    fun getTextArtifactFile(jobId: UUID): File {
        return File(getJobDir(jobId), "source_text.txt")
    }

    private fun deleteDirectoryContentsSafely(target: File) {
        if (!target.exists()) return
        if (target.isDirectory) {
            target.listFiles()?.forEach { child -> deleteDirectoryContentsSafely(child) }
        }
        target.delete()
    }

    fun clearJobStaging(jobId: UUID) {
        synchronized(stagingLock) {
            val dir = File(getBaseDir(), jobId.toString())
            deleteDirectoryContentsSafely(dir)
        }
    }

    fun sweepStaleJobs(activeJobIds: Set<UUID>) {
        synchronized(stagingLock) {
            val baseDir = getBaseDir()
            val activeIdsStr = activeJobIds.map { it.toString() }.toSet()

            baseDir.listFiles()?.forEach { file ->
                if (file.isDirectory && !activeIdsStr.contains(file.name)) {
                    deleteDirectoryContentsSafely(file)
                }
            }
        }
    }

    fun stageText(jobId: UUID, text: String) {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        require(normalized.isNotBlank()) { "There is no text to export" }
        require(normalized.length <= MAX_TEXT_CHARACTERS) { "Text exceeds audio export limit" }
        getTextArtifactFile(jobId).writeText(normalized, Charsets.UTF_8)
    }

    companion object { const val MAX_TEXT_CHARACTERS = 2_000_000 }
}
