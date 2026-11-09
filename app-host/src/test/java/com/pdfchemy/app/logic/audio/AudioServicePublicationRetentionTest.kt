package com.pdfchemy.app.logic.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.pdfchemy.app.logic.OutputPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowTextToSpeech
import java.io.File
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [AudioServiceLifecycleTest.PcmTtsShadow::class])
class AudioServicePublicationRetentionTest {
    private class OutputProvider(private val output: File) : ContentProvider() {
        var deletes = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/wav"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deletes++
            return if (output.delete()) 1 else 0
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") {
                delete(Uri.EMPTY, null, null)
                return Bundle()
            }
            return super.call(method, arg, extras)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals("wt", mode)
            return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }

    private fun TestScope.driveUntil(condition: () -> Boolean) {
        repeat(500) {
            runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Service did not reach the expected publication/cleanup boundary")
    }

    private enum class End { CANCEL, DESTROY, ERROR }

    private fun retainsPublishedOutput(end: End) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildService(AudioExportService::class.java).create()
        val service = controller.get()
        val id = UUID.randomUUID()
        val store = AudioExportDependencies.jobStore
        val staging = AudioExportDependencies.stagingManager
        val jobDirectory = staging.getJobDir(id)
        val output = File(service.cacheDir, "retention-$id.wav")
        val authority = "audio.retention.test"
        val uri = DocumentsContract.buildDocumentUri(authority, id.toString())
        val provider = OutputProvider(output)
        val published = CompletableDeferred<Unit>()
        var destroyed = false
        try {
            provider.attachInfo(service, ProviderInfo().apply { this.authority = authority })
            ShadowContentResolver.registerProviderInternal(authority, provider)
            store.createJob(id)
            staging.stageText(id, "Synthetic audio service retention regression")
            OutputPolicy.registerFreshCopy(uri)
            AudioExportDependencies.registerDestination(id, uri)
            service.afterPublicationForTesting = {
                published.complete(Unit)
                if (end == End.ERROR) throw java.io.IOException("Synthetic error after publication")
                awaitCancellation()
            }
            ShadowTextToSpeech.addVoice(Voice("offline-test", Locale.getDefault(), Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, emptySet()))
            service.onStartCommand(Intent().putExtra(AudioExportService.EXTRA_JOB_ID, id.toString()), 0, 1)
            runCurrent()
            shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
            driveUntil { published.isCompleted }
            assertEquals(uri.toString(), store.getJob(id)!!.outputUri)
            when (end) {
                End.CANCEL -> store.updateState(id, AudioExportState.CANCELLING)
                End.DESTROY -> { controller.destroy(); destroyed = true }
                End.ERROR -> Unit
            }
            driveUntil { service.exportJobForTesting?.isCompleted == true }
            assertEquals(if (end == End.CANCEL) AudioExportState.CANCELLED else AudioExportState.FAILED,
                store.getJob(id)!!.state)
            assertEquals(uri.toString(), store.getJob(id)!!.outputUri)
            assertNotNull(store.getJob(id)!!.publication)
            assertEquals("Completed audio must not be deleted by service cleanup", 0, provider.deletes)
            assertEquals(48L, output.length())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.readBytes().copyOfRange(44, 48))
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertFalse(jobDirectory.exists())
            assertTrue(runCatching { AudioExportDependencies.destination(id) }.isFailure)
        } finally {
            if (!destroyed) controller.destroy()
            driveUntil { service.exportJobForTesting?.isCompleted != false }
            service.afterPublicationForTesting = null
            staging.clearJobStaging(id)
            AudioExportDependencies.forgetDestination(id)
            store.removeJob(id)
            OutputPolicy.unregisterFreshCopy(uri)
            output.delete()
            Dispatchers.resetMain()
        }
    }

    @Test fun cancellationAfterPublicationRetainsTheSavedFileAndUri() = retainsPublishedOutput(End.CANCEL)
    @Test fun serviceDestructionAfterPublicationRetainsTheSavedFileAndUri() = retainsPublishedOutput(End.DESTROY)
    @Test fun resultDeliveryErrorRetainsTheSavedFileAndUri() = retainsPublishedOutput(End.ERROR)
}
