package com.pdfchemy.app.logic.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.security.PublicationCoordinator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AudioPublicationOwnershipTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var output: File
    private lateinit var wav: File
    private lateinit var destination: Uri
    private lateinit var provider: OutputProvider
    private class OutputProvider(private val output: File) : ContentProvider() {
        var failDelete = false
        var deleteCalls = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/wav"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            deleteCalls++
            return if (!failDelete && output.delete()) 1 else 0
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") {
                if (delete(Uri.EMPTY, null, null) == 0) throw FileNotFoundException("Delete failed")
                return Bundle()
            }
            return super.call(method, arg, extras)
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }
    @Before fun setup() {
        PublicationCoordinator.resetForTesting()
        AudioRegularFileMetadata.install()
        output = File.createTempFile("audio_destination", ".wav", context.cacheDir)
        wav = File.createTempFile("audio_source", ".wav", context.cacheDir).apply { writeBytes(ByteArray(65539) { (it % 251).toByte() }) }
        destination = Uri.parse("content://audio-ownership.test/document/${UUID.randomUUID()}")
        provider = OutputProvider(output)
        provider.attachInfo(context, ProviderInfo().apply { authority = destination.authority })
        ShadowContentResolver.registerProviderInternal(destination.authority!!, provider)
    }
    @After fun cleanup() {
        AudioRegularFileMetadata.clear()
        PublicationCoordinator.resetForTesting()
        if (OutputPolicy.isFreshCopy(destination)) OutputPolicy.unregisterFreshCopy(destination)
        output.delete(); wav.delete()
    }
    @Test fun cancellationAfterPublicationPreservesOutputDespiteCachedFreshFlag() = runBlocking {
        OutputPolicy.registerFreshCopy(destination)
        val id = UUID.randomUUID()
        AudioExportDependencies.registerDestination(id, destination)
        val cachedFresh = AudioExportDependencies.isFreshDestination(id)
        val published = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            try {
                AudioExportDependencies.publishDestination(context, id, wav) {}
                published.complete(Unit)
                awaitCancellation() // Service has not yet recorded COMPLETED.
            } finally {
                AudioOutputPublisher.deletePartial(context, destination, cachedFresh)
            }
        }
        try {
            withTimeout(10_000) { published.await() }
            job.cancelAndJoin()
            assertTrue("Published WAV was deleted by stale provenance", output.exists())
            assertArrayEquals(wav.readBytes(), output.readBytes())
            assertFalse(OutputPolicy.isFreshCopy(destination))
            assertEquals(0, provider.deleteCalls)
        } finally { job.cancelAndJoin(); AudioExportDependencies.forgetDestination(context, id) }
    }
    @Test fun unknownDestinationCannotBeDeletedEvenWithStaleFreshFlag() {
        AudioOutputPublisher.deletePartial(context, destination, true)
        assertTrue(output.exists())
        assertEquals(0, provider.deleteCalls)
    }
    @Test fun freshPartialIsDeletedOnce() {
        OutputPolicy.registerFreshCopy(destination)
        AudioOutputPublisher.deletePartial(context, destination, true)
        AudioOutputPublisher.deletePartial(context, destination, true)
        assertFalse(output.exists())
        assertFalse(OutputPolicy.isFreshCopy(destination))
        assertEquals(1, provider.deleteCalls)
    }
    @Test fun failedDeletionRetainsOwnershipForRetry() {
        OutputPolicy.registerFreshCopy(destination)
        provider.failDelete = true
        AudioOutputPublisher.deletePartial(context, destination, true)
        assertTrue(output.exists())
        assertTrue("Failed deletion lost cleanup ownership", OutputPolicy.isFreshCopy(destination))
        provider.failDelete = false
        AudioOutputPublisher.deletePartial(context, destination, true)
        assertFalse(output.exists())
    }
    @Test fun activeWriterProtectsFreshPartialUntilItStops() {
        OutputPolicy.registerFreshCopy(destination)
        val writer = PublicationCoordinator.acquire(listOf(destination), 1)
        try {
            AudioOutputPublisher.deletePartial(context, destination, true)
            assertTrue(output.exists())
            assertEquals(0, provider.deleteCalls)
        } finally { writer.close() }
        AudioOutputPublisher.deletePartial(context, destination, true)
        assertFalse(output.exists())
    }
}
