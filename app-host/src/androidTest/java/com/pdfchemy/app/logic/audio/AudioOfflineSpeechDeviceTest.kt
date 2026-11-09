package com.pdfchemy.app.logic.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.MainActivity
import com.pdfchemy.app.logic.OfflineSpeechUnavailableException
import com.pdfchemy.app.logic.OutputPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioOfflineSpeechDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val control = Uri.parse("content://com.pdfchemy.app.test.speech")
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }
    private fun engineState(): Bundle = requireNotNull(context.contentResolver.call(control, "state", null, null))

    private fun export(networkOnly: Boolean, installed: Boolean) = runBlocking {
        val previousEngine = shell("settings get secure tts_default_synth")
        require(previousEngine == "null" || previousEngine.matches(Regex("[A-Za-z0-9_.]+")))
        val id = UUID.randomUUID()
        val destination = Uri.parse("content://com.pdfchemy.app.security.outputfixture/offline-audio-$id")
        val jobs = AudioExportDependencies.jobStore
        var started = false
        try {
            context.contentResolver.call(control, "configure", null, Bundle().apply {
                putBoolean("networkOnly", networkOnly)
                putBoolean("offlineInstalled", installed)
                putString("locale", Locale.getDefault().toLanguageTag())
            })
            shell("settings put secure tts_default_synth com.pdfchemy.app.test")
            assertEquals("com.pdfchemy.app.test", shell("settings get secure tts_default_synth"))
            context.contentResolver.call(destination, "configure", destination.lastPathSegment, Bundle().apply { putBoolean("allowDelete", true) })
            AudioExportDependencies.initialize(context)
            jobs.createJob(id)
            OutputPolicy.registerFreshCopy(destination)
            AudioExportDependencies.registerDestination(id, destination)
            AudioExportDependencies.stagingManager.stageText(id, "Synthetic WAV offline policy phrase.")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val intent = Intent(host, AudioExportService::class.java).putExtra(AudioExportService.EXTRA_JOB_ID, id.toString())
                    if (Build.VERSION.SDK_INT >= 26) host.startForegroundService(intent) else host.startService(intent)
                    started = true
                }
                withTimeout(30_000) {
                    while (jobs.getJob(id)?.state !in AudioExportJobStore.terminalStates ||
                        runCatching { AudioExportDependencies.destination(id) }.isSuccess) delay(50)
                }
            }
            val fixture = engineState()
            assertTrue("The actual service must query the installed fixture engine", fixture.getInt("queries") > 0)
            val output = requireNotNull(context.contentResolver.call(destination, "state", destination.lastPathSegment, null))
            if (!networkOnly && installed) {
                assertEquals(jobs.getJob(id)?.error?.toString(), AudioExportState.COMPLETED, jobs.getJob(id)?.state)
                assertEquals(destination.toString(), jobs.getJob(id)?.outputUri)
                assertTrue(fixture.getStringArrayList("texts")!!.single().contains("Synthetic WAV offline policy phrase."))
                assertTrue(fixture.getStringArrayList("voices")!!.all { it == "offline" })
                val written = requireNotNull(context.contentResolver.call(destination, "awaitWritten", destination.lastPathSegment, null))
                val bytes = requireNotNull(written.getByteArray("bytes"))
                assertEquals(3244, bytes.size)
                assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
                assertFalse(output.getBoolean("deleted"))
            } else {
                assertEquals(AudioExportState.FAILED, jobs.getJob(id)?.state)
                assertTrue(jobs.getJob(id)?.error is OfflineSpeechUnavailableException)
                assertTrue("No document text may reach an unavailable/network voice", fixture.getStringArrayList("texts")!!.isEmpty())
                assertEquals(0, output.getInt("opens"))
                assertTrue(output.getBoolean("deleted"))
            }
            assertFalse(OutputPolicy.isFreshCopy(destination))
        } finally {
            try {
                if (started && jobs.getJob(id)?.state !in AudioExportJobStore.terminalStates) {
                    jobs.updateState(id, AudioExportState.CANCELLING)
                    withTimeout(10_000) { while (runCatching { AudioExportDependencies.destination(id) }.isSuccess) delay(50) }
                }
                AudioExportDependencies.forgetDestination(context, id)
                jobs.removeJob(id)
            } finally {
                if (previousEngine == "null") shell("settings delete secure tts_default_synth")
                else shell("settings put secure tts_default_synth $previousEngine")
            }
        }
    }

    @Test fun wavServiceRejectsNetworkOnlyEngineWithoutSubmittingText() = export(true, true)
    @Test fun wavServiceRejectsUninstalledOfflineVoiceWithoutSubmittingText() = export(false, false)
    @Test fun wavServiceOverridesNetworkDefaultAndPublishesOfflinePcm() = export(false, true)
}
