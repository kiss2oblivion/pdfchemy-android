package com.pdfchemy.app.logic.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import com.pdfchemy.app.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.UUID

class AudioExportService : Service() {
    companion object {
        const val EXTRA_JOB_ID = "extra_job_id"
        private const val ACTION_CANCEL = "com.pdfchemy.app.audio.CANCEL"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_export_channel"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store get() = AudioExportDependencies.jobStore
    private val staging get() = AudioExportDependencies.stagingManager
    private var activeId: UUID? = null
    private var pipeline: Job? = null
    private var tts: TextToSpeech? = null
    private var writer: PcmWriter? = null
    private var abort: AudioExportAbort? = null

    override fun onCreate() {
        super.onCreate()
        AudioExportDependencies.initialize(this)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.audio_export_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        updateForeground(null)
    }

    private fun updateForeground(id: UUID?) {
        val notification = notification(id)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, AudioForegroundPolicy.typeForSdk(Build.VERSION.SDK_INT))
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun notification(id: UUID?): Notification {
        val builder = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this))
            .setContentTitle(getString(R.string.audio_export_title))
            .setContentText(getString(R.string.audio_export_notification))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
        if (id != null) {
            val cancel = PendingIntent.getService(this, NOTIFICATION_ID,
                Intent(this, AudioExportService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB_ID, id.toString()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_delete, getString(android.R.string.cancel), cancel).build())
        }
        return builder.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = runCatching { UUID.fromString(intent?.getStringExtra(EXTRA_JOB_ID)) }.getOrNull()
        if (id == null) { if (activeId == null) stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_CANCEL) {
            if (id == activeId) abort?.cancel()
            else if (activeId == null) stopSelf()
            return START_NOT_STICKY
        }
        if (activeId != null) {
            if (id != activeId && store.updateState(id, AudioExportState.FAILED, IllegalStateException("Another audio export is running"))) {
                scope.launch(NonCancellable + Dispatchers.IO) {
                    runCatching { AudioOutputPublisher.deletePartial(this@AudioExportService, AudioExportDependencies.destination(id)) }
                    staging.clearJobStaging(id); AudioExportDependencies.forgetDestination(id)
                }
            }
            return START_NOT_STICKY
        }
        if (!store.updateState(id, AudioExportState.INITIALIZING)) {
            scope.launch(Dispatchers.IO) {
                runCatching { AudioOutputPublisher.deletePartial(this@AudioExportService, AudioExportDependencies.destination(id)) }
                staging.clearJobStaging(id); AudioExportDependencies.forgetDestination(id)
                if (store.getJob(id)?.state == AudioExportState.CANCELLING) store.updateState(id, AudioExportState.CANCELLED)
                withContext(Dispatchers.Main) { stopSelf() }
            }
            return START_NOT_STICKY
        }
        activeId = id
        updateForeground(id)
        abort = AudioExportAbort(id, store,
            invalidate = { error -> writer?.invalidate(error) },
            stopTts = { tts?.stop() },
            cancelPipeline = { pipeline?.cancel() }
        )
        pipeline = scope.launch { export(id) }
        return START_NOT_STICKY
    }

    private suspend fun export(id: UUID) {
        val cancellationWatcher = scope.launch {
            store.getJobFlow(id)?.first { it.state == AudioExportState.CANCELLING }
            abort?.cancel()
        }
        try {
            val destination = AudioExportDependencies.destination(id)
            val initialized = CompletableDeferred<Int>()
            tts = TextToSpeech(this) { initialized.complete(it) }
            check(withTimeout(30_000) { initialized.await() } == TextToSpeech.SUCCESS) { "TTS initialization failed" }
            val engine = requireNotNull(tts)
            val offline = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
            val voice = offline.firstOrNull { it.locale == Locale.getDefault() }
                ?: offline.firstOrNull { it.locale.language == Locale.getDefault().language }
                ?: throw IllegalStateException("No installed offline voice for the current language")
            check(engine.setVoice(voice) == TextToSpeech.SUCCESS) { "Unable to select offline voice" }
            check(store.updateState(id, AudioExportState.SYNTHESIZING))
            val chunks = withContext(Dispatchers.IO) {
                val text = staging.getTextArtifactFile(id).readText(Charsets.UTF_8)
                require(text.length <= AudioStagingManager.MAX_TEXT_CHARACTERS)
                AudioTextChunker.chunkText(text, TextToSpeech.getMaxSpeechInputLength()).filter { it.isNotBlank() }
            }
            require(chunks.isNotEmpty()) { "There is no text to export" }
            val total = chunks.sumOf { it.length }
            val projector = QuotaProjector(total)
            val raw = staging.getMasterRawFile(id)
            val wav = staging.getFinalWavFile(id)
            val pcm = PcmWriter(id, UUID.randomUUID(), raw, CoroutineScope(currentCoroutineContext() + Dispatchers.IO),
                isJobActive = { store.getJob(id)?.state == AudioExportState.SYNTHESIZING })
            writer = pcm
            check(engine.setOnUtteranceProgressListener(TtsPcmAssembler(pcm)) == TextToSpeech.SUCCESS)
            var processed = 0
            store.updateProgress(id, 0, total, 0)
            for ((index, chunk) in chunks.withIndex()) {
                currentCoroutineContext().ensureActive()
                val (utterance, drained) = pcm.beginChunk(index)
                val sink = staging.getTempChunkFile(id, index)
                check(engine.synthesizeToFile(chunk, Bundle(), sink, utterance) == TextToSpeech.SUCCESS) { "TTS rejected synthesis request" }
                val bytes = withTimeout(120_000) { drained.await() }
                // onDone alone cannot release this sink; drained acknowledges the application's writer flush.
                withContext(Dispatchers.IO) { sink.delete() }
                projector.recordChunk(chunk.length, bytes, pcm.format)
                val safe = withContext(Dispatchers.IO) { projector.isSafe(pcm.format, raw.parentFile!!.usableSpace) }
                check(safe) { "Projected audio exceeds available storage or RIFF limits" }
                processed += chunk.length
                store.updateProgress(id, processed, total, projector.projectedBytes(pcm.format))
            }
            withContext(Dispatchers.IO) { pcm.closeAndJoin() }
            check(store.updateState(id, AudioExportState.ASSEMBLING))
            withContext(Dispatchers.IO) { WavWriter.assemble(raw, wav, pcm.format) }
            check(store.updateState(id, AudioExportState.PUBLISHING))
            withContext(Dispatchers.IO) { AudioOutputPublisher.publish(this@AudioExportService, wav, destination) }
            currentCoroutineContext().ensureActive()
            check(store.updateState(id, AudioExportState.COMPLETED, outputUri = destination.toString()))
        } catch (error: CancellationException) {
            if (store.getJob(id)?.state == AudioExportState.CANCELLING) {
                // CANCELLED is published only after descriptor/stage cleanup below.
            } else store.updateState(id, AudioExportState.FAILED, IllegalStateException("Audio export interrupted", error))
        } catch (error: Exception) {
            writer?.invalidate(error)
            store.updateState(id, AudioExportState.FAILED, error)
        } finally {
            cancellationWatcher.cancel()
            writer?.invalidate()
            tts?.stop(); tts?.shutdown(); tts = null
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { writer?.closeAndJoin() }
                if (store.getJob(id)?.state != AudioExportState.COMPLETED) {
                    runCatching { AudioOutputPublisher.deletePartial(this@AudioExportService, AudioExportDependencies.destination(id)) }
                }
                staging.clearJobStaging(id)
                AudioExportDependencies.forgetDestination(id)
                if (store.getJob(id)?.state == AudioExportState.CANCELLING) store.updateState(id, AudioExportState.CANCELLED)
            }
            withContext(NonCancellable + Dispatchers.Main) {
                writer = null; activeId = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                scope.cancel()
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        abort?.timeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf() // Required immediately; IO cleanup continues in the pipeline's NonCancellable finally.
    }

    override fun onDestroy() {
        abort?.interrupted()
        tts?.stop()
        if (pipeline == null) scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
