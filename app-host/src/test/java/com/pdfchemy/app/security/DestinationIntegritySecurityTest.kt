package com.pdfchemy.app.security

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.DestinationProvenance
import com.pdfchemy.app.logic.OutputDestination
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.PdfCompressor
import com.pdfchemy.app.logic.PdfManipulator
import com.pdfchemy.app.logic.PublicationState
import com.pdfchemy.app.logic.audio.AudioExportDependencies
import com.pdfchemy.app.logic.audio.AudioExportJobStore
import com.pdfchemy.app.logic.audio.AudioExportState
import com.pdfchemy.app.logic.audio.AudioOutputPublisher
import com.pdfchemy.app.ui.MainViewModel
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DestinationIntegritySecurityTest {

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `existing destination seeded with bytes survives pre-publication failure`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source.pdf")
        val existingDestination = Uri.parse("content://fixture/existing_user_doc.pdf")

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), existingDestination) } returns documentFile
        var deleteCalled = false
        every { documentFile.delete() } answers { deleteCalled = true; true }

        mockkObject(PdfCompressor)
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("Pre-publication parser failure"))

        // Provenance is EXISTING_OR_UNKNOWN (default for external/picker URIs)
        assertFalse(OutputPolicy.isFreshCopy(existingDestination))

        viewModel.compressPdf(application, source, existingDestination)

        // Await error state
        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Error }
        }

        // Must NOT delete existing document
        assertFalse("Existing document must survive pre-publication failure!", deleteCalled)
        verify(exactly = 0) { documentFile.delete() }
    }

    @Test
    fun `existing destination seeded with bytes survives cancellation`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source.pdf")
        val existingDestination = Uri.parse("content://fixture/existing_invoice.pdf")

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), existingDestination) } returns documentFile
        var deleteCalled = false
        every { documentFile.delete() } answers { deleteCalled = true; true }

        val started = CompletableDeferred<Unit>()
        mockkObject(PdfCompressor)
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            awaitCancellation()
        }

        assertFalse(OutputPolicy.isFreshCopy(existingDestination))

        viewModel.compressPdf(application, source, existingDestination)
        started.await()

        viewModel.cancelOperation(application)
        assertSame(MainViewModel.UiState.Idle, viewModel.uiState.value)

        // Wait to allow any asynchronous cleanup to run
        delay(100)

        assertFalse("Existing document must survive cancellation!", deleteCalled)
        verify(exactly = 0) { documentFile.delete() }
    }

    @Test
    fun `fresh incomplete destination is deleted on cancellation after writer stops`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source.pdf")
        val freshDestination = Uri.parse("content://fixture/fresh_copy_${UUID.randomUUID()}.pdf")

        // Register as proven fresh copy
        OutputPolicy.registerFreshCopy(freshDestination)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDestination) } returns documentFile
        val deleted = CompletableDeferred<Unit>()
        val writerFinished = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        every { documentFile.delete() } answers {
            assertTrue("Deletion must not race with active writer!", writerFinished.isCompleted)
            deleted.complete(Unit)
            true
        }

        val started = CompletableDeferred<Unit>()
        mockkObject(PdfCompressor)
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    allowFinish.await()
                    writerFinished.complete(Unit)
                }
            }
        }

        viewModel.compressPdf(application, source, freshDestination)
        started.await()

        viewModel.cancelOperation(application)
        assertSame(MainViewModel.UiState.Idle, viewModel.uiState.value)
        assertFalse(deleted.isCompleted)

        allowFinish.complete(Unit)
        withTimeout(5_000) { deleted.await() }

        // Provenance must be unregistered after deletion
        assertFalse(OutputPolicy.isFreshCopy(freshDestination))
    }

    @Test
    fun `fresh incomplete destination is deleted on worker error`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source.pdf")
        val freshDestination = Uri.parse("content://fixture/fresh_copy_err.pdf")

        OutputPolicy.registerFreshCopy(freshDestination)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDestination) } returns documentFile
        val deleted = CompletableDeferred<Unit>()
        every { documentFile.delete() } answers { deleted.complete(Unit); true }

        mockkObject(PdfCompressor)
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("Worker crash"))

        viewModel.compressPdf(application, source, freshDestination)

        withTimeout(5_000) { deleted.await() }
        withTimeout(5_000) { viewModel.uiState.first { it is MainViewModel.UiState.Error } }

        assertFalse(OutputPolicy.isFreshCopy(freshDestination))
    }

    @Test
    fun `successful write publishes output and protects it from subsequent cleanup`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source.pdf")
        val freshDestination = Uri.parse("content://fixture/fresh_successful.pdf")

        OutputPolicy.registerFreshCopy(freshDestination)

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDestination) } returns documentFile
        every { documentFile.delete() } answers {
            fail("Published destination must never be deleted!")
            true
        }

        mockkObject(PdfCompressor)
        val dummyReport = PdfCompressor.CompressionReport(1000L, 5, false, false)
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(dummyReport)

        viewModel.compressPdf(application, source, freshDestination)

        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Success }
        }

        // Classification must no longer be fresh!
        assertFalse("Fresh copy classification must be removed after successful publication!", OutputPolicy.isFreshCopy(freshDestination))

        // Setting error or idle afterwards must not delete the published output
        viewModel.cancelOperation(application)
        delay(50)

        verify(exactly = 0) { documentFile.delete() }
    }

    @Test
    fun `published URI reused in subsequent operation survives subsequent failure`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source1 = Uri.parse("content://fixture/source1.pdf")
        val reusedDestination = Uri.parse("content://fixture/reused_output.pdf")

        // 1. Initial creation as fresh copy
        OutputPolicy.registerFreshCopy(reusedDestination)
        assertTrue(OutputPolicy.isFreshCopy(reusedDestination))

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), reusedDestination) } returns documentFile
        every { documentFile.delete() } answers {
            fail("Reused published destination must NEVER be deleted!")
            true
        }

        mockkObject(PdfCompressor)
        val dummyReport = PdfCompressor.CompressionReport(1000L, 5, false, false)
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(dummyReport)

        // Op 1 publishes successfully
        viewModel.compressPdf(application, source1, reusedDestination)
        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Success }
        }

        assertFalse("Destination must no longer be fresh after Op 1 publication", OutputPolicy.isFreshCopy(reusedDestination))

        // 2. Op 2 reuses the published destination, but fails
        val source2 = Uri.parse("content://fixture/source2.pdf")
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("Op 2 failed write"))

        viewModel.compressPdf(application, source2, reusedDestination)
        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Error }
        }

        delay(100)
        verify(exactly = 0) { documentFile.delete() }
    }

    @Test
    fun `stale operation cleanup cannot delete newer operation output`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)

        val freshDestOp1 = Uri.parse("content://fixture/op1_fresh.pdf")
        val freshDestOp2 = Uri.parse("content://fixture/op2_fresh.pdf")
        OutputPolicy.registerFreshCopy(freshDestOp1)
        OutputPolicy.registerFreshCopy(freshDestOp2)

        mockkStatic(DocumentFile::class)
        val doc1 = mockk<DocumentFile>()
        val doc2 = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDestOp1) } returns doc1
        every { DocumentFile.fromSingleUri(any(), freshDestOp2) } returns doc2

        val doc1Deleted = CompletableDeferred<Unit>()
        every { doc1.delete() } answers { doc1Deleted.complete(Unit); true }
        every { doc2.delete() } answers {
            fail("Operation 2 output must NEVER be deleted by Operation 1 cancellation!")
            true
        }

        mockkObject(PdfManipulator)
        val op1Started = CompletableDeferred<Unit>()
        coEvery { PdfManipulator.mergePdfs(any(), any(), freshDestOp1) } coAnswers {
            op1Started.complete(Unit)
            awaitCancellation()
        }

        // Start Op 1
        viewModel.mergePdfs(application, listOf(Uri.parse("content://fixture/src1.pdf")), freshDestOp1)
        op1Started.await()

        // Cancel Op 1
        viewModel.cancelOperation(application)

        // Immediately start Op 2 with freshDestOp2
        coEvery { PdfManipulator.mergePdfs(any(), any(), freshDestOp2) } coAnswers {
            // Op 2 completes
        }
        viewModel.mergePdfs(application, listOf(Uri.parse("content://fixture/src2.pdf")), freshDestOp2)

        withTimeout(5_000) { doc1Deleted.await() }
        delay(100)

        // Doc 2 must NOT have been touched
        verify(exactly = 0) { doc2.delete() }
        verify(atLeast = 1) { doc1.delete() }
    }

    @Test
    fun `source equals destination is rejected and source is protected`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val sameUri = Uri.parse("content://fixture/same_document.pdf")

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), sameUri) } returns documentFile
        every { documentFile.delete() } answers {
            fail("Source document must never be deleted!")
            true
        }

        viewModel.compressPdf(application, sameUri, sameUri)

        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Error }
        }

        verify(exactly = 0) { documentFile.delete() }
    }

    @Test
    fun `audio export existing destination survives rejection and failure`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val existingAudio = Uri.parse("content://fixture/pre_existing_audio.wav")
        val jobId = UUID.randomUUID()

        mockkStatic(DocumentFile::class)
        val docFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), existingAudio) } returns docFile
        every { docFile.delete() } answers {
            fail("Existing audio destination must not be deleted on failure!")
            true
        }

        // Register with provenance = EXISTING (isFreshCopy = false)
        AudioExportDependencies.registerDestination(jobId, existingAudio, isFreshCopy = false)
        assertFalse(AudioExportDependencies.isFreshDestination(jobId))

        // Pre-publication deletion attempt (e.g. initialization rejection or error)
        AudioOutputPublisher.deletePartial(context, existingAudio, isFreshCopy = false)

        verify(exactly = 0) { docFile.delete() }
        AudioExportDependencies.forgetDestination(jobId)
    }

    @Test
    fun `audio export fresh incomplete destination is cleaned up`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshAudio = Uri.parse("content://fixture/fresh_incomplete_audio.wav")
        val jobId = UUID.randomUUID()

        mockkStatic(DocumentFile::class)
        val docFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshAudio) } returns docFile
        var deleted = false
        every { docFile.delete() } answers { deleted = true; true }

        OutputPolicy.registerFreshCopy(freshAudio)
        AudioExportDependencies.registerDestination(jobId, freshAudio, isFreshCopy = true)
        assertTrue(AudioExportDependencies.isFreshDestination(jobId))

        AudioOutputPublisher.deletePartial(context, freshAudio, isFreshCopy = true)

        assertTrue("Fresh incomplete audio file must be deleted", deleted)
        verify(exactly = 1) { docFile.delete() }
        AudioExportDependencies.forgetDestination(jobId)
    }
}
