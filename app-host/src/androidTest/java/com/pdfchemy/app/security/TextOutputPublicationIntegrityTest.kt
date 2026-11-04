package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.TextOutputPublisher
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class TextOutputPublicationIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun target(failure: String? = null, fresh: Boolean = true, block: Boolean = false): Uri {
        val uri = Uri.parse("content://com.pdfchemy.app.security.outputfixture/text_${System.nanoTime()}")
        context.contentResolver.call(uri, "configure", uri.lastPathSegment, Bundle().apply {
            putString("failure", failure); putBoolean("allowDelete", true); putBoolean("block", block)
        })
        if (fresh) OutputPolicy.registerFreshCopy(uri)
        return uri
    }
    private fun state(uri: Uri) = requireNotNull(context.contentResolver.call(uri, "state", uri.lastPathSegment, null))
    private fun await(uri: Uri, event: String) { context.contentResolver.call(uri, event, uri.lastPathSegment, null) }

    @Test fun utf8TextPublishesThroughActualPipeAndRetiresFreshness() = runBlocking {
        val uri = target(); val text = "Public Ω text & literal <tags>\n".repeat(5_000)
        TextOutputPublisher.publish(context, uri) { text }; await(uri, "awaitWritten")
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), state(uri).getByteArray("bytes"))
        assertEquals(1, state(uri).getInt("opens")); assertFalse(state(uri).getBoolean("deleted"))
        assertFalse(OutputPolicy.isFreshCopy(uri)); assertFalse(OutputPolicy.deleteIfFresh(context, uri))
    }
    @Test fun conversionFailureDeletesCopyWithoutOpeningIt() = runBlocking {
        val uri = target()
        assertTrue(runCatching { TextOutputPublisher.publish(context, uri) { throw IOException("conversion failed") } }.isFailure)
        assertEquals(0, state(uri).getInt("opens")); assertTrue(state(uri).getBoolean("deleted"))
        assertFalse(OutputPolicy.isFreshCopy(uri))
    }
    @Test fun providerOpenFailureDeletesOnlyItsOwnedCopy() = runBlocking {
        val uri = target("open")
        assertTrue(runCatching { TextOutputPublisher.publish(context, uri) { "text" } }.isFailure)
        assertTrue(state(uri).getBoolean("deleted")); assertFalse(OutputPolicy.isFreshCopy(uri))
    }
    @Test fun partialPipeFailureCannotPublishTruncatedText() = runBlocking {
        val uri = target("partial")
        assertTrue(runCatching { TextOutputPublisher.publish(context, uri) { "x".repeat(1_000_000) } }.isFailure)
        await(uri, "awaitWritten")
        assertTrue(state(uri).getBoolean("deleted")); assertFalse(OutputPolicy.isFreshCopy(uri))
    }
    @Test fun existingDestinationRemainsIdenticalWithoutAnOpen() = runBlocking {
        val uri = target("open", fresh = false)
        assertTrue(runCatching { TextOutputPublisher.publish(context, uri) { "replacement" } }.isFailure)
        assertEquals(0, state(uri).getInt("opens")); assertFalse(state(uri).getBoolean("deleted"))
        assertEquals("existing destination", String(state(uri).getByteArray("bytes")!!))
    }
    @Test fun cancellationBeforeProductionCleansAndPropagates() = runBlocking {
        val uri = target(); val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) { TextOutputPublisher.publish(context, uri) { entered.complete(Unit); awaitCancellation() } }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(0, state(uri).getInt("opens"))
        assertTrue(state(uri).getBoolean("deleted")); assertFalse(OutputPolicy.isFreshCopy(uri))
        assertFalse(PublicationCoordinator.hasActiveWriter(uri))
    }
    @Test fun cancellationDuringOpenCleansOnlyAfterWriterReturns() = runBlocking {
        val uri = target(block = true)
        val job = launch(Dispatchers.IO) { TextOutputPublisher.publish(context, uri) { "text" } }
        try {
            withContext(Dispatchers.IO) { await(uri, "awaitOpen") }; job.cancel()
            assertFalse("Provider call is still active", state(uri).getBoolean("deleted"))
        } finally {
            context.contentResolver.call(uri, "release", uri.lastPathSegment, null); job.cancelAndJoin()
        }
        assertTrue(state(uri).getBoolean("deleted")); assertFalse(PublicationCoordinator.hasActiveWriter(uri))
    }
}
