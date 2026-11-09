package com.pdfchemy.app.logic.audio

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.security.FreshOutputRegistry
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioDestinationAdmissionTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = Uri.parse("content://audio-admission/${UUID.randomUUID()}")
    private val foreignOwner = Any()
    private val jobId = UUID.randomUUID()
    private var opens = 0
    private var deletes = 0
    private val output = ByteArrayOutputStream()

    private fun configure() {
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.isDocumentUri(context, uri) } returns false
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(uri, "wt") } answers { opens++; output }
        every { resolver.delete(uri, null, null) } answers { deletes++; 1 }
    }

    @After fun cleanup() {
        AudioExportDependencies.forgetDestination(context, jobId)
        FreshOutputRegistry.published(listOf(uri), foreignOwner)
        // Registration compensation is exercised explicitly by tests that own a job.
        OutputPolicy.unregisterFreshCopy(uri)
        unmockkAll()
    }

    private fun rejectedPublish(freshHint: Boolean? = null) = runBlocking {
        configure()
        val wav = File.createTempFile("audio_admission_", ".wav", app.cacheDir).apply { writeText("synthetic bytes") }
        try {
            val failure = runCatching {
                if (freshHint == null) AudioOutputPublisher.publish(context, wav, uri)
                else AudioOutputPublisher.publish(context, wav, uri, freshHint)
            }.exceptionOrNull()
            assertNotNull("An unowned destination must be rejected before destructive open", failure)
            assertEquals("Admission must precede provider open", 0, opens)
            assertEquals("Rejection must preserve the destination", 0, deletes)
        } finally { wav.delete() }
    }

    @Test fun unknownDestinationIsRejectedBeforeOpen() = rejectedPublish()
    @Test fun forgedFreshBooleanCannotAuthorizeAnUnknownDestination() = rejectedPublish(true)
    @Test fun retiredPublishedDestinationCannotBeReopened() {
        OutputPolicy.registerFreshCopy(uri)
        OutputPolicy.markPublished(uri)
        rejectedPublish(true)
    }
    @Test fun anotherOperationsClaimCannotBeOpenedOrDeleted() {
        OutputPolicy.registerFreshCopy(uri)
        FreshOutputRegistry.claimKnown(listOf(uri), foreignOwner)
        rejectedPublish(true)
        assertTrue(FreshOutputRegistry.isClaimed(uri))
    }

    @Test fun registrationRejectsUnknownDestinationEvenWithForgedFreshBoolean() {
        val failure = runCatching { AudioExportDependencies.registerDestination(jobId, uri, true) }.exceptionOrNull()
        assertNotNull("Registration must validate host provenance", failure)
        assertTrue(runCatching { AudioExportDependencies.destination(jobId) }.isFailure)
    }

    @Test fun registrationRejectsAnotherOperationsClaim() {
        OutputPolicy.registerFreshCopy(uri)
        FreshOutputRegistry.claimKnown(listOf(uri), foreignOwner)
        assertNotNull(runCatching { AudioExportDependencies.registerDestination(jobId, uri) }.exceptionOrNull())
        assertTrue(FreshOutputRegistry.isClaimed(uri))
        assertTrue(runCatching { AudioExportDependencies.destination(jobId) }.isFailure)
    }

    @Test fun registrationClaimsBeforeStagingAndRejectsCompetingJob() {
        configure()
        OutputPolicy.registerFreshCopy(uri)
        AudioExportDependencies.registerDestination(jobId, uri)
        assertTrue(FreshOutputRegistry.isClaimed(uri))
        assertNotNull(runCatching { AudioExportDependencies.registerDestination(UUID.randomUUID(), uri) }.exceptionOrNull())
        assertEquals(uri, AudioExportDependencies.destination(jobId))
        assertEquals(0, opens)
        assertEquals(0, deletes)
        AudioExportDependencies.forgetDestination(context, jobId)
        assertEquals(1, deletes)
        assertFalse(OutputPolicy.isFreshCopy(uri))
    }

    @Test fun duplicateJobDoesNotClaimOrDeleteItsSecondDestination() {
        configure()
        val other = Uri.parse("content://audio-admission/${UUID.randomUUID()}")
        OutputPolicy.registerFreshCopy(uri)
        OutputPolicy.registerFreshCopy(other)
        try {
            AudioExportDependencies.registerDestination(jobId, uri)
            assertNotNull(runCatching { AudioExportDependencies.registerDestination(jobId, other) }.exceptionOrNull())
            assertFalse(FreshOutputRegistry.isClaimed(other))
            assertTrue(OutputPolicy.isFreshCopy(other))
            assertEquals(uri, AudioExportDependencies.destination(jobId))
        } finally { OutputPolicy.unregisterFreshCopy(other) }
    }

    @Test fun registeredOwnerPublishesExactlyOnceAndForgettingPreservesBytes() = runBlocking {
        configure()
        OutputPolicy.registerFreshCopy(uri)
        AudioExportDependencies.registerDestination(jobId, uri)
        val wav = File.createTempFile("audio_owned_", ".wav", app.cacheDir).apply { writeText("complete synthetic WAV") }
        try {
            var receipts = 0
            AudioExportDependencies.publishDestination(context, jobId, wav) { receipts++ }
            assertEquals(1, opens)
            assertEquals(1, receipts)
            assertArrayEquals(wav.readBytes(), output.toByteArray())
            assertFalse(AudioExportDependencies.isFreshDestination(jobId))
            assertNotNull(runCatching { AudioExportDependencies.publishDestination(context, jobId, wav) {} }.exceptionOrNull())
            assertEquals(1, opens)
            AudioExportDependencies.forgetDestination(context, jobId)
            assertEquals(0, deletes)
        } finally { wav.delete() }
    }

    @Test fun openFailureReleasesWriterBeforeOwnedCleanup() = runBlocking {
        configure()
        OutputPolicy.registerFreshCopy(uri)
        every { resolver.openOutputStream(uri, "wt") } answers { opens++; throw java.io.FileNotFoundException("after truncation") }
        val wav = File.createTempFile("audio_open_", ".wav", app.cacheDir).apply { writeText("source stays intact") }
        try {
            assertNotNull(runCatching { AudioOutputPublisher.publish(context, wav, uri) }.exceptionOrNull())
            assertEquals(1, opens)
            assertEquals(1, deletes)
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertEquals("source stays intact", wav.readText())
        } finally { wav.delete() }
    }

    @Test fun forgettingAnActiveRegisteredPublisherCannotDeleteOrDropItsClaim() = runBlocking {
        configure()
        OutputPolicy.registerFreshCopy(uri)
        AudioExportDependencies.registerDestination(jobId, uri)
        every { resolver.openOutputStream(uri, "wt") } answers {
            opens++
            assertTrue(runCatching { AudioExportDependencies.forgetDestination(context, jobId) }.isFailure)
            assertEquals(uri, AudioExportDependencies.destination(jobId))
            assertTrue(FreshOutputRegistry.isClaimed(uri))
            assertEquals(0, deletes)
            output
        }
        val wav = File.createTempFile("audio_active_", ".wav", app.cacheDir).apply { writeText("complete bytes") }
        try {
            AudioExportDependencies.publishDestination(context, jobId, wav) {}
            AudioExportDependencies.forgetDestination(context, jobId)
            assertEquals(0, deletes)
            assertArrayEquals(wav.readBytes(), output.toByteArray())
        } finally { wav.delete() }
    }

    @Test fun closeFailureNeverRetiresPublicationAndCleansOwnedOutput() = runBlocking {
        configure()
        OutputPolicy.registerFreshCopy(uri)
        every { resolver.openOutputStream(uri, "wt") } returns object : ByteArrayOutputStream() {
            override fun close() { throw java.io.IOException("synthetic close failure") }
        }
        val wav = File.createTempFile("audio_close_", ".wav", app.cacheDir).apply { writeText("source stays intact") }
        try {
            var receipts = 0
            assertNotNull(runCatching { AudioOutputPublisher.publish(context, wav, uri) { receipts++ } }.exceptionOrNull())
            assertEquals(0, receipts)
            assertEquals(1, deletes)
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertEquals("source stays intact", wav.readText())
        } finally { wav.delete() }
    }
}
