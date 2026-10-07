package com.pdfchemy.app.logic.audio

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.os.Bundle
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.ParcelFileDescriptor
import org.robolectric.shadows.ShadowContentResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.File
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [AudioServiceLifecycleTest.PcmTtsShadow::class])
class AudioServiceLifecycleTest {
    private class OutputProvider(private val output: File) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/wav"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = if (output.delete()) 1 else 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals("wt", mode)
            return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }
    @Test fun `network-only voices fail without unsafe fallback`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(AudioExportService::class.java).create()
        val service = controller.get(); val id = UUID.randomUUID()
        val store = AudioExportDependencies.jobStore; val staging = AudioExportDependencies.stagingManager
        try {
            store.createJob(id); staging.stageText(id, "Offline voice regression")
            AudioExportDependencies.registerDestination(id, Uri.parse("content://missing.test/$id"))
            ShadowTextToSpeech.addVoice(Voice("network-test", Locale.getDefault(), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, true, emptySet()))
            service.onStartCommand(Intent().putExtra(AudioExportService.EXTRA_JOB_ID, id.toString()), 0, 1)
            runCurrent()
            shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
            runCurrent()
            assertEquals(AudioExportState.FAILED, store.getJob(id)!!.state)
            assertTrue(store.getJob(id)!!.error!!.message!!.contains("offline voice"))
            val source = staging.getTextArtifactFile(id)
            repeat(100) { runCurrent(); if (source.exists()) Thread.sleep(10) }
        } finally {
            controller.destroy(); staging.clearJobStaging(id)
            AudioExportDependencies.forgetDestination(id); store.removeJob(id); Dispatchers.resetMain()
        }
    }

    @Implements(TextToSpeech::class)
    class PcmTtsShadow : ShadowTextToSpeech() {
        @Implementation
        override fun synthesizeToFile(text: CharSequence, params: Bundle, file: File, utteranceId: String): Int {
            // Deliberately NOT a WAV. The application's authoritative data must come from callbacks.
            file.writeText("arbitrary engine container; do not concatenate")
            utteranceProgressListener.onBeginSynthesis(utteranceId, 22050, 2, 1)
            utteranceProgressListener.onAudioAvailable(utteranceId, byteArrayOf(1, 2, 3, 4))
            utteranceProgressListener.onDone(utteranceId)
            return TextToSpeech.SUCCESS
        }
    }

    @Test fun `complete service pipeline publishes callback PCM rather than engine sink`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(AudioExportService::class.java).create()
        val service = controller.get(); val id = UUID.randomUUID()
        val store = AudioExportDependencies.jobStore; val staging = AudioExportDependencies.stagingManager
        val destination = File(service.cacheDir, "exports/service-$id.wav")
        destination.parentFile!!.mkdirs()
        try {
            store.createJob(id); staging.stageText(id, "Service pipeline regression text")
            val uri = Uri.parse("content://audio.unit.test/output.wav")
            val provider = OutputProvider(destination)
            provider.attachInfo(service, ProviderInfo().apply { authority = "audio.unit.test" })
            ShadowContentResolver.registerProviderInternal("audio.unit.test", provider)
            AudioExportDependencies.registerDestination(id, uri)
            ShadowTextToSpeech.addVoice(Voice("offline-test", Locale.getDefault(), Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
            service.onStartCommand(Intent().putExtra(AudioExportService.EXTRA_JOB_ID, id.toString()), 0, 1)
            runCurrent()
            shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
            repeat(500) {
                runCurrent()
                if (store.getJob(id)!!.state !in AudioExportJobStore.terminalStates) Thread.sleep(10)
            }
            runCurrent()
            assertEquals(store.getJob(id)!!.error?.toString(), AudioExportState.COMPLETED, store.getJob(id)!!.state)
            assertEquals(uri.toString(), store.getJob(id)!!.outputUri)
            assertEquals(48L, destination.length())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination.readBytes().copyOfRange(44, 48))
        } finally {
            controller.destroy(); staging.clearJobStaging(id); destination.delete()
            AudioExportDependencies.forgetDestination(id); store.removeJob(id)
            Dispatchers.resetMain()
        }
    }

    @Test fun `media timeout aborts initializing service and cleans staged files`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(AudioExportService::class.java).create()
        val service = controller.get()
        val id = UUID.randomUUID()
        val store = AudioExportDependencies.jobStore
        val staging = AudioExportDependencies.stagingManager
        try {
            store.createJob(id)
            AudioExportDependencies.registerDestination(id, Uri.parse("content://missing.test/$id"))
            staging.stageText(id, "Timeout regression text")
            service.onStartCommand(Intent().putExtra(AudioExportService.EXTRA_JOB_ID, id.toString()), 0, 1)
            runCurrent() // Pipeline is awaiting the TTS initialization callback.
            service.onTimeout(1, 8192)
            assertEquals(AudioExportState.FAILED, store.getJob(id)!!.state)
            assertTrue(store.getJob(id)!!.error!!.message!!.contains("timed out"))
            advanceUntilIdle()
            // Cleanup uses real IO; wait for it without pretending it ran in virtual time.
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                repeat(100) {
                    if (!java.io.File(service.cacheDir, "audio-export/$id").exists()) return@withContext
                    Thread.sleep(10)
                }
            }
            runCurrent()
            assertFalse(java.io.File(service.cacheDir, "audio-export/$id").exists())
        } finally {
            controller.destroy(); staging.clearJobStaging(id)
            AudioExportDependencies.forgetDestination(id); store.removeJob(id)
            Dispatchers.resetMain()
        }
    }
}
