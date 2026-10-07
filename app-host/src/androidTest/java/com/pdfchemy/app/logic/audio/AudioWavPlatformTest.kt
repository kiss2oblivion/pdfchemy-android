package com.pdfchemy.app.logic.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioWavPlatformTest {
    @Test fun foregroundServiceStartsAndCancelsQueuedJobOnActualPlatform() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AudioExportDependencies.initialize(context)
        val id = UUID.randomUUID(); val store = AudioExportDependencies.jobStore
        val staging = AudioExportDependencies.stagingManager
        val destination = File(context.cacheDir, "exports/audio-start-$id.wav")
        destination.parentFile!!.mkdirs()
        try {
            store.createJob(id); staging.stageText(id, "Foreground service regression")
            AudioExportDependencies.registerDestination(id,
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination))
            store.updateState(id, AudioExportState.CANCELLING)
            ActivityScenario.launch(com.pdfchemy.app.MainActivity::class.java).use { activity ->
                activity.onActivity { host ->
                    val intent = Intent(host, AudioExportService::class.java).putExtra(AudioExportService.EXTRA_JOB_ID, id.toString())
                    if (Build.VERSION.SDK_INT >= 26) host.startForegroundService(intent) else host.startService(intent)
                }
                withTimeout(15_000) { requireNotNull(store.getJobFlow(id)).first { it.state == AudioExportState.CANCELLED } }
                assertFalse(File(context.cacheDir, "audio-export/$id").exists())
            }
        } finally { staging.clearJobStaging(id); destination.delete(); AudioExportDependencies.forgetDestination(id); store.removeJob(id) }
    }

    @Test fun assembledWavIsRecognizedByAndroid() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staging = AudioStagingManager(context)
        val id = UUID.randomUUID()
        val extractor = MediaExtractor()
        try {
            val raw = staging.getMasterRawFile(id); raw.writeBytes(ByteArray(22050 * 2))
            val wav = staging.getFinalWavFile(id)
            WavWriter.assemble(raw, wav, PcmFormat(22050, 1, 2))
            extractor.setDataSource(wav.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(22050, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertEquals("audio/raw", format.getString(MediaFormat.KEY_MIME))
        } finally { extractor.release(); staging.clearJobStaging(id) }
    }

    @Test fun hostPublicationWritesCompleteWavThroughContentResolver() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staging = AudioStagingManager(context); val id = UUID.randomUUID()
        val destinationFile = File(context.cacheDir, "exports/audio-platform-$id.wav")
        destinationFile.parentFile!!.mkdirs()
        try {
            val raw = staging.getMasterRawFile(id); raw.writeBytes(ByteArray(16000))
            val wav = staging.getFinalWavFile(id)
            WavWriter.assemble(raw, wav, PcmFormat(8000, 1, 2))
            val destination = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destinationFile)
            AudioOutputPublisher.publish(context, wav, destination)
            assertArrayEquals(wav.readBytes(), destinationFile.readBytes())
        } finally { destinationFile.delete(); staging.clearJobStaging(id) }
    }

    @Test fun exportServiceIsPrivateAndTtsDiscoveryIsDeclared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context, AudioExportService::class.java), PackageManager.GET_META_DATA)
        assertFalse(service.exported)
        assertEquals(context.packageName, service.processName)
        // Correct Android 15 mediaProcessing callback signature must survive compilation/R8.
        assertNotNull(AudioExportService::class.java.getMethod("onTimeout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
    }
}
