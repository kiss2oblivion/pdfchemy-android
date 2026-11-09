package com.pdfchemy.app.logic.audio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.security.FreshOutputRegistry
import com.pdfchemy.app.security.PublicationCoordinator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioDestinationAdmissionDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val base = Uri.parse("content://com.pdfchemy.app.security.outputfixture")
    private fun state(uri: Uri, method: String = "state", extras: Bundle? = null): Bundle =
        requireNotNull(context.contentResolver.call(base, method, uri.lastPathSegment, extras))

    private fun destination(extras: Bundle = Bundle()): Uri {
        val uri = Uri.withAppendedPath(base, "audio-admission-${UUID.randomUUID()}")
        extras.putBoolean("allowDelete", true)
        state(uri, "configure", extras)
        return uri
    }

    private fun wav(large: Boolean = false): File =
        File.createTempFile("audio_admission_", ".wav", context.cacheDir).apply {
            val pcm = ByteArray(if (large) 2 * 1024 * 1024 else 4) { (it % 127).toByte() }
            writeBytes(WavWriter.header(PcmFormat(22050, 1, 2), pcm.size.toLong()) + pcm)
        }

    private fun rejectsWithoutOpen(freshHint: Boolean, claimed: Boolean = false, retired: Boolean = false) = runBlocking {
        val uri = destination(Bundle().apply { putString("failure", "open") })
        val original = state(uri).getByteArray("bytes")
        val source = wav()
        val sourceBytes = source.readBytes()
        val owner = Any()
        try {
            if (claimed || retired) OutputPolicy.registerFreshCopy(uri)
            if (claimed) FreshOutputRegistry.claimKnown(listOf(uri), owner)
            if (retired) OutputPolicy.markPublished(uri)
            assertNotNull(runCatching { AudioOutputPublisher.publish(context, source, uri, freshHint) }.exceptionOrNull())
            val result = state(uri)
            assertEquals("A destructive provider must receive no open", 0, result.getInt("opens"))
            assertFalse(result.getBoolean("deleted"))
            assertArrayEquals(original, result.getByteArray("bytes"))
            assertArrayEquals(sourceBytes, source.readBytes())
            if (claimed) assertTrue(FreshOutputRegistry.isClaimed(uri))
        } finally {
            FreshOutputRegistry.published(listOf(uri), owner)
            OutputPolicy.unregisterFreshCopy(uri)
            source.delete()
        }
    }

    @Test fun existingDestinationIsRejectedBeforeTruncatingProviderOpen() = rejectsWithoutOpen(false)
    @Test fun forgedFreshHintCannotOpenAnUnknownDestination() = rejectsWithoutOpen(true)
    @Test fun publishedDestinationCannotBeReused() = rejectsWithoutOpen(true, retired = true)
    @Test fun foreignClaimCannotBeOpenedOrDeleted() = rejectsWithoutOpen(true, claimed = true)

    @Test fun ownedOpenFailureAfterTruncationCleansOnlyItsOutput() = runBlocking {
        val uri = destination(Bundle().apply { putString("failure", "open") })
        val source = wav()
        val expected = source.readBytes()
        OutputPolicy.registerFreshCopy(uri)
        try {
            assertNotNull(runCatching { AudioOutputPublisher.publish(context, source, uri) }.exceptionOrNull())
            assertEquals(1, state(uri).getInt("opens"))
            assertTrue(state(uri).getBoolean("deleted"))
            assertFalse(PublicationCoordinator.hasActiveWriter(uri))
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertArrayEquals(expected, source.readBytes())
        } finally { OutputPolicy.unregisterFreshCopy(uri); source.delete() }
    }

    @Test fun ownedPartialPipeFailureClosesWriterBeforeDeletion() = runBlocking {
        val uri = destination(Bundle().apply { putString("failure", "partial") })
        val source = wav(large = true)
        val expected = source.readBytes()
        OutputPolicy.registerFreshCopy(uri)
        try {
            assertNotNull(runCatching { AudioOutputPublisher.publish(context, source, uri) }.exceptionOrNull())
            state(uri, "awaitWritten")
            assertTrue(state(uri).getBoolean("deleted"))
            assertFalse(PublicationCoordinator.hasActiveWriter(uri))
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertArrayEquals(expected, source.readBytes())
        } finally { OutputPolicy.unregisterFreshCopy(uri); source.delete() }
    }

    @Test fun cancellationDuringProviderOpenCleansOwnedDestinationAfterReturn() = runBlocking {
        val uri = destination(Bundle().apply { putBoolean("block", true) })
        val source = wav()
        val expected = source.readBytes()
        OutputPolicy.registerFreshCopy(uri)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { AudioOutputPublisher.publish(context, source, uri) }
        try {
            state(uri, "awaitOpen")
            job.cancel()
            state(uri, "release")
            withTimeout(5_000) { job.join() }
            state(uri, "awaitWritten")
            assertTrue(job.isCancelled)
            assertTrue(state(uri).getBoolean("deleted"))
            assertFalse(PublicationCoordinator.hasActiveWriter(uri))
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertArrayEquals(expected, source.readBytes())
        } finally {
            state(uri, "release")
            scope.cancel()
            withTimeout(5_000) { job.join() }
            OutputPolicy.unregisterFreshCopy(uri)
            source.delete()
        }
    }

    @Test fun failedJobLaunchReleasesOnlyItsClaimWithoutOpening() {
        val id = UUID.randomUUID()
        val uri = destination()
        OutputPolicy.registerFreshCopy(uri)
        try {
            AudioExportDependencies.registerDestination(id, uri)
            assertTrue(FreshOutputRegistry.isClaimed(uri))
            assertNotNull(runCatching { AudioExportDependencies.registerDestination(UUID.randomUUID(), uri) }.exceptionOrNull())
            assertEquals(0, state(uri).getInt("opens"))
            AudioExportDependencies.forgetDestination(context, id)
            assertTrue(state(uri).getBoolean("deleted"))
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertTrue(runCatching { AudioExportDependencies.destination(id) }.isFailure)
        } finally { AudioExportDependencies.forgetDestination(context, id); OutputPolicy.unregisterFreshCopy(uri) }
    }
}
