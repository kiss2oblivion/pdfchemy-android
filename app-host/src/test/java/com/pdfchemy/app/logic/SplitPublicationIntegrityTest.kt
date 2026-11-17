package com.pdfchemy.app.logic

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.security.FreshOutputRegistry
import com.pdfchemy.app.security.PublicationCoordinator
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

/** Exercise cleanup in the public wrappers, including the commit-to-result cancellation gap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SplitPublicationIntegrityTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val fixtures = mutableListOf<Fixture>()

    private enum class Operation {
        PAGES, BLANKS, BOOKMARKS, IMAGES;

        suspend fun execute(context: Context, source: Uri, directory: DocumentFile): List<Uri> = when (this) {
            PAGES -> PdfManipulator.splitPdf(context, source, directory, "synthetic")
            BLANKS -> PdfManipulator.splitByBlankPages(context, source, directory, "synthetic")
            BOOKMARKS -> PdfManipulator.splitByBookmarks(context, source, directory, "synthetic")
            IMAGES -> PdfManipulator.convertPdfToImages(context, source, directory, "synthetic")
        }
    }

    private inner class Fixture {
        val source = Uri.parse("content://split-fixture/source.pdf")
        val uris = List(2) { Uri.parse("content://split-fixture/${UUID.randomUUID()}") }
        val documents = List(2) { mockk<DocumentFile>() }
        val directory = mockk<DocumentFile>()
        val deletes = IntArray(2)
        val deletionAllowed = BooleanArray(2) { true }
        var deletionFailure: IOException? = null
        val owner = Any()
        var handle: PublicationCoordinator.PublicationHandle? = null

        init {
            fixtures.add(this)
            mockkObject(PdfGateway)
            mockkStatic(DocumentFile::class)
            every { directory.exists() } returns true
            every { directory.isDirectory } returns true
            every { directory.canWrite() } returns true
            every { directory.listFiles() } returns emptyArray()
            var next = 0
            every { directory.createFile(any(), any()) } answers { documents[next++] }
            documents.forEachIndexed { index, document ->
                every { document.uri } returns uris[index]
                every { DocumentFile.fromSingleUri(any(), uris[index]) } returns document
                every { document.delete() } answers {
                    deletes[index]++
                    deletionFailure?.let { throw it }
                    deletionAllowed[index]
                }
            }
            coEvery { PdfGateway.executeEngine(any(), "GET_PAGE_COUNT", any(), any(), any()) } returns PageCountContract(2)
            coEvery { PdfGateway.executeEngine(any(), "PLAN_SPLIT_BLANK", any(), any(), any()) } returns
                PlanSplitBlankContract(true, listOf(listOf("1"), listOf("2")))
            coEvery { PdfGateway.executeEngine(any(), "PLAN_SPLIT_BOOKMARKS", any(), any(), any()) } returns
                PlanSplitBookmarksContract(true, listOf(listOf("1"), listOf("2")))
        }

        fun publishAll() = uris.forEach(OutputPolicy::markPublished)

        suspend fun fail(operation: Operation, beforeFailure: () -> Unit = {}): Throwable? {
            val failure = IOException("Synthetic failure at result delivery")
            coEvery { PdfGateway.executeEngineBatch(any(), any(), any(), any(), any()) } coAnswers {
                beforeFailure()
                throw failure
            }
            val caught = runCatching { operation.execute(context, source, directory) }.exceptionOrNull()
            assertTrue("$operation must preserve the failure type: $caught", caught is IOException)
            assertEquals(failure.message, caught?.message)
            // Coroutine stack-trace recovery may copy an exception and retain the original as its cause.
            assertTrue("$operation replaced the original failure", generateSequence(caught) { it.cause }.any { it === failure })
            return caught
        }

        fun close() {
            handle?.close()
            FreshOutputRegistry.published(uris, owner)
            uris.forEach(OutputPolicy::unregisterFreshCopy)
        }
    }

    @After fun cleanup() {
        try { fixtures.forEach { it.close() } }
        finally { unmockkAll() }
    }

    private fun cancellationAfterPublicationPreservesOutputs(operation: Operation) = runBlocking<Unit> {
        val fixture = Fixture()
        val published = CompletableDeferred<Unit>()
        coEvery { PdfGateway.executeEngineBatch(any(), any(), any(), any(), any()) } coAnswers {
            fixture.publishAll()
            published.complete(Unit)
            awaitCancellation()
        }
        val job = launch(Dispatchers.IO) { operation.execute(context, fixture.source, fixture.directory) }
        try {
            withTimeout(5_000) { published.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertArrayEquals("$operation deleted a published output", intArrayOf(0, 0), fixture.deletes)
            fixture.uris.forEach { assertFalse(OutputPolicy.isFreshCopy(it)) }
        } finally { job.cancelAndJoin() }
    }

    @Test fun plainSplitPreservesPublishedOutputsAfterCancellation() =
        cancellationAfterPublicationPreservesOutputs(Operation.PAGES)

    @Test fun blankSplitPreservesPublishedOutputsAfterCancellation() =
        cancellationAfterPublicationPreservesOutputs(Operation.BLANKS)

    @Test fun bookmarkSplitPreservesPublishedOutputsAfterCancellation() =
        cancellationAfterPublicationPreservesOutputs(Operation.BOOKMARKS)

    @Test fun imageExportPreservesPublishedOutputsAfterCancellation() =
        cancellationAfterPublicationPreservesOutputs(Operation.IMAGES)

    @Test fun errorAfterPublicationPreservesEveryCompletedOutput() = runBlocking {
        for (operation in Operation.values()) {
            val fixture = Fixture()
            fixture.fail(operation) { fixture.publishAll() }
            assertArrayEquals("$operation deleted a published output", intArrayOf(0, 0), fixture.deletes)
        }
    }

    @Test fun failureCleansEachUnpublishedFreshOutputOnce() = runBlocking {
        for (operation in Operation.values()) {
            val fixture = Fixture()
            fixture.fail(operation)
            assertArrayEquals("$operation cleanup must run once per incomplete copy", intArrayOf(1, 1), fixture.deletes)
            fixture.uris.forEach { assertFalse(OutputPolicy.isFreshCopy(it)) }
        }
    }

    @Test fun mixedPublicationStatesPreserveCompletedAndRemoveIncompleteOutputs() = runBlocking {
        for (operation in Operation.values()) {
            val fixture = Fixture()
            fixture.fail(operation) { OutputPolicy.markPublished(fixture.uris.first()) }
            assertArrayEquals("$operation ignored current publication state", intArrayOf(0, 1), fixture.deletes)
            fixture.uris.forEach { assertFalse(OutputPolicy.isFreshCopy(it)) }
        }
    }

    @Test fun refusedDeletionPreservesProvenanceForLaterCleanup() = runBlocking {
        for (operation in Operation.values()) {
            val fixture = Fixture()
            fixture.deletionAllowed[0] = false
            fixture.fail(operation)
            assertArrayEquals("$operation repeated a refused deletion", intArrayOf(1, 1), fixture.deletes)
            assertTrue(OutputPolicy.isFreshCopy(fixture.uris[0]))
            assertFalse(OutputPolicy.isFreshCopy(fixture.uris[1]))
            fixture.deletionAllowed[0] = true
            assertTrue(OutputPolicy.deleteIfFresh(context, fixture.uris[0]))
            assertFalse(OutputPolicy.isFreshCopy(fixture.uris[0]))
        }
    }

    @Test fun activeWriterPreventsCleanupUntilItStops() = runBlocking {
        for (operation in Operation.values()) {
            val fixture = Fixture()
            fixture.fail(operation) {
                fixture.handle = PublicationCoordinator.acquire(fixture.uris, 1)
            }
            assertArrayEquals("$operation deleted while a writer was active", intArrayOf(0, 0), fixture.deletes)
            fixture.uris.forEach { assertTrue(OutputPolicy.isFreshCopy(it)) }
            fixture.handle!!.close()
            fixture.handle = null
            fixture.uris.forEach { assertTrue(OutputPolicy.deleteIfFresh(context, it)) }
            assertArrayEquals(intArrayOf(1, 1), fixture.deletes)
        }
    }

    @Test fun anotherOwnersClaimCannotBeDeletedByWrapperCleanup() = runBlocking {
        for (operation in Operation.values()) {
            val fixture = Fixture()
            fixture.fail(operation) { FreshOutputRegistry.claimKnown(fixture.uris, fixture.owner) }
            assertArrayEquals("$operation deleted another owner's output", intArrayOf(0, 0), fixture.deletes)
            fixture.uris.forEach { assertTrue(FreshOutputRegistry.isClaimed(it)) }
        }
    }

    @Test fun cleanupExceptionPreservesOriginalFailureAndFreshness() = runBlocking {
        for (operation in Operation.values()) {
            val fixture = Fixture()
            fixture.deletionFailure = IOException("Synthetic provider deletion error")
            fixture.fail(operation)
            assertArrayEquals("$operation repeated failed cleanup", intArrayOf(1, 1), fixture.deletes)
            fixture.uris.forEach { assertTrue(OutputPolicy.isFreshCopy(it)) }
        }
    }
}
