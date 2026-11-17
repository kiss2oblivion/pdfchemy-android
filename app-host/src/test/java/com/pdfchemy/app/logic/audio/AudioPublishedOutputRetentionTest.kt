package com.pdfchemy.app.logic.audio

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AudioPublishedOutputRetentionTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://audio-retention/${UUID.randomUUID()}")
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val document = mockk<DocumentFile>()
    private val owner = Any()
    private var deletes = 0
    private var allowed = true
    private var deleteFailure: IOException? = null

    private fun configure() {
        mockkObject(AudioOutputPublisher)
        mockkStatic(DocumentFile::class)
        every { context.contentResolver } returns resolver
        every { DocumentFile.fromSingleUri(context, uri) } returns document
        every { document.delete() } answers {
            deletes++
            deleteFailure?.let { throw it }
            allowed
        }
        OutputPolicy.registerFreshCopy(uri)
    }

    @After fun cleanup() {
        FreshOutputRegistry.published(listOf(uri), owner)
        OutputPolicy.unregisterFreshCopy(uri)
        unmockkAll()
    }

    @Test fun publishedOutputSurvivesCleanupUsingCachedFreshProvenance() = runBlocking {
        configure()
        val id = UUID.randomUUID()
        val bytes = "Synthetic publication bytes".toByteArray()
        val wav = File.createTempFile("audio_retention_", ".wav", app.cacheDir).apply { writeBytes(bytes) }
        val output = ByteArrayOutputStream()
        every { AudioOutputPublisher.openWholeFile(context, uri) } returns output
        try {
            AudioExportDependencies.registerDestination(id, uri)
            val cachedFreshness = AudioExportDependencies.isFreshDestination(id)
            AudioExportDependencies.publishDestination(context, id, wav) {}
            assertArrayEquals(bytes, output.toByteArray())
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertFalse(AudioExportDependencies.isFreshDestination(id))
            AudioOutputPublisher.deletePartial(context, uri, cachedFreshness)
            assertEquals("Completed output must survive a stale cleanup request", 0, deletes)
        } finally {
            AudioExportDependencies.forgetDestination(context, id)
            wav.delete()
        }
    }

    @Test fun refusedDeletionKeepsProvenanceForAnExplicitRetry() {
        configure()
        allowed = false
        AudioOutputPublisher.deletePartial(context, uri, true)
        assertTrue("Refused deletion must retain provenance", OutputPolicy.isFreshCopy(uri))
        allowed = true
        AudioOutputPublisher.deletePartial(context, uri, true)
        assertEquals(2, deletes)
        assertFalse(OutputPolicy.isFreshCopy(uri))
    }

    @Test fun cleanupIsIdempotentAfterSuccessfulDeletion() {
        configure()
        AudioOutputPublisher.deletePartial(context, uri, true)
        AudioOutputPublisher.deletePartial(context, uri, true)
        assertEquals("Retired provenance must prevent a second delete", 1, deletes)
    }

    @Test fun claimedOutputRemainsWithItsOwner() {
        configure()
        FreshOutputRegistry.claimKnown(listOf(uri), owner)
        AudioOutputPublisher.deletePartial(context, uri, true)
        assertEquals(0, deletes)
        assertTrue(FreshOutputRegistry.isClaimed(uri))
    }

    @Test fun providerDeletionErrorRetainsProvenance() {
        configure()
        deleteFailure = IOException("Synthetic provider deletion failure")
        AudioOutputPublisher.deletePartial(context, uri, true)
        assertEquals(1, deletes)
        assertTrue("Deletion error must not retire freshness", OutputPolicy.isFreshCopy(uri))
    }
}
