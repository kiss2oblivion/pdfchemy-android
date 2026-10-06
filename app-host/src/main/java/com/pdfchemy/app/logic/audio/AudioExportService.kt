package com.pdfchemy.app.logic.audio

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.UUID

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class AudioExportService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_DESTINATION_URI = "extra_destination_uri"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_export_channel"
        private const val TAG = "AudioExportService"
    }

    // In a real app, these might be injected by Hilt/Dagger, but we instantiate or access a singleton here.
    // For this implementation, we will assume a global job store exists or create a local one.
    // Ideally we should have a singleton registry. We will simulate injection by using a companion object on store if needed,
    // or just instantiate here for the sake of the architecture skeleton.
    // Let's use a global instance pattern if needed, but for now we will instantiate.
    private lateinit var jobStore: AudioExportJobStore
    private lateinit var stagingManager: AudioStagingManager
    
    private var tts: TextToSpeech? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var activeJobId: UUID? = null
    private var destinationUri: Uri? = null

    // We will inject the store via a static provider in a real app.
    // For testability, let's assume there's a global AudioExportDependencies.
    // Here we'll just instantiate them, as this is Phase 1 architecture implementation.
    
    override fun onCreate() {
        super.onCreate()
        jobStore = AudioExportDependencies.jobStore
        stagingManager = AudioExportDependencies.stagingManager
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobIdString = intent?.getStringExtra(EXTRA_JOB_ID)
        val destUri = intent?.getParcelableExtra<Uri>(EXTRA_DESTINATION_URI)

        if (jobIdString == null || destUri == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        activeJobId = UUID.fromString(jobIdString)
        destinationUri = destUri

        jobStore.updateState(activeJobId!!, AudioExportState.INITIALIZING)

        tts = TextToSpeech(this, this)

        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            serviceScope.launch {
                initializeTtsAndStart()
            }
        } else {
            activeJobId?.let { jobStore.updateState(it, AudioExportState.FAILED, Exception("TTS Init failed")) }
            stopSelf()
        }
    }

    private suspend fun initializeTtsAndStart() {
        val jobId = activeJobId ?: return
        
        // Select an offline voice
        val voices = tts?.voices
        val offlineVoice = voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale == Locale.getDefault() }
            ?: voices?.firstOrNull { !it.isNetworkConnectionRequired }
        
        if (offlineVoice != null) {
            tts?.voice = offlineVoice
        } else {
            // Fallback, not guaranteed offline but we tried
        }

        jobStore.updateState(jobId, AudioExportState.SYNTHESIZING)

        val textFile = stagingManager.getTextArtifactFile(jobId)
        if (!textFile.exists()) {
            jobStore.updateState(jobId, AudioExportState.FAILED, Exception("Source text not found"))
            stopSelf()
            return
        }

        val text = textFile.readText()
        val chunks = AudioTextChunker.chunkText(text, TextToSpeech.getMaxSpeechInputLength())
        
        val projector = QuotaProjector(text.length)
        jobStore.updateProgress(jobId, 0, text.length, 0L)

        val masterRawFile = stagingManager.getMasterRawFile(jobId)
        val finalWavFile = stagingManager.getFinalWavFile(jobId)

        var currentChunkIndex = 0
        var chunkCompleteSignal = kotlinx.coroutines.channels.Channel<String>(1)

        val assembler = TtsPcmAssembler(
            jobId = jobId,
            masterRawFile = masterRawFile,
            jobStore = jobStore,
            coroutineScope = serviceScope,
            onChunkComplete = { uttId ->
                chunkCompleteSignal.trySend(uttId)
            },
            onError = { uttId, errorMsg ->
                jobStore.updateState(jobId, AudioExportState.FAILED, Exception(errorMsg))
                chunkCompleteSignal.trySend("ERROR")
            }
        )

        tts?.setOnUtteranceProgressListener(assembler)

        for ((index, chunk) in chunks.withIndex()) {
            val job = jobStore.getJob(jobId)
            if (job?.state == AudioExportState.CANCELLING || job?.state == AudioExportState.CANCELLED) {
                handleCancellation(jobId)
                return
            }

            val chunkTempFile = stagingManager.getTempChunkFile(jobId, index)
            val startTime = System.currentTimeMillis()
            
            val params = android.os.Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "chunk_$index")
            
            tts?.synthesizeToFile(chunk, params, chunkTempFile, "chunk_$index")
            
            // Suspend until chunk completes
            val signal = chunkCompleteSignal.receive()
            if (signal == "ERROR") {
                stopSelf()
                return
            }

            val synthesisTime = System.currentTimeMillis() - startTime
            val bytesWritten = if (chunkTempFile.exists()) chunkTempFile.length() else 0L

            projector.recordChunk(chunk.length, synthesisTime, bytesWritten)
            chunkTempFile.delete() // Delete the chunk temp file after processing it

            // Check Quota
            val sampleRate = assembler.getSampleRate()
            val channels = assembler.getChannelCount()
            if (sampleRate != -1 && channels != -1) {
                val bytesPerSample = assembler.getCalculatedBytesPerSample()
                if (!projector.isProjectionSafe(sampleRate, channels, bytesPerSample)) {
                    jobStore.updateState(jobId, AudioExportState.FAILED, Exception("Projected size exceeds limits"))
                    stopSelf()
                    return
                }
                
                jobStore.updateProgress(
                    jobId = jobId,
                    processedCharacters = (job?.processedCharacters ?: 0) + chunk.length,
                    projectedSizeBytes = projector.getProjectedTotalBytes(sampleRate, channels, bytesPerSample)
                )
            }
        }

        val job = jobStore.getJob(jobId)
        if (job?.state == AudioExportState.CANCELLING || job?.state == AudioExportState.CANCELLED) {
            handleCancellation(jobId)
            return
        }

        // Assembly
        jobStore.updateState(jobId, AudioExportState.ASSEMBLING)
        try {
            assembler.finishAndWriteWavHeader(finalWavFile)
        } catch (e: Exception) {
            jobStore.updateState(jobId, AudioExportState.FAILED, e)
            stopSelf()
            return
        }

        // Publishing
        jobStore.updateState(jobId, AudioExportState.PUBLISHING)
        try {
            contentResolver.openOutputStream(destinationUri!!, "wt")?.use { outputStream ->
                finalWavFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw Exception("Could not open destination URI")
            
            jobStore.updateState(jobId, AudioExportState.COMPLETED)
        } catch (e: Exception) {
            jobStore.updateState(jobId, AudioExportState.FAILED, e)
        } finally {
            stagingManager.clearJobStaging(jobId)
            stopSelf()
        }
    }

    private fun handleCancellation(jobId: UUID) {
        tts?.stop()
        stagingManager.clearJobStaging(jobId)
        jobStore.updateState(jobId, AudioExportState.CANCELLED)
        stopForeground(true)
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        activeJobId?.let {
            jobStore.updateState(it, AudioExportState.FAILED, Exception("FGS Timeout Reached"))
            handleCancellation(it)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Export",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Exporting Audio")
            .setContentText("Synthesizing document...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
