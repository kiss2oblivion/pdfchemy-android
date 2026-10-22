package com.pdfchemy.app.security

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.DestinationProvenance
import com.pdfchemy.app.logic.OutputDestination
import com.pdfchemy.app.logic.ImageCompressionResult
import com.pdfchemy.app.logic.ImageCompressor
import com.pdfchemy.app.logic.ImageOutputFormat
import com.pdfchemy.app.logic.MarkdownEngine
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.PdfCompressor
import com.pdfchemy.app.logic.PdfEditor
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
    fun `fresh incomplete destination is deleted on onCleared only after writer stops`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source.pdf")
        val freshDestination = Uri.parse("content://fixture/fresh_copy_oncleared_${UUID.randomUUID()}.pdf")

        OutputPolicy.registerFreshCopy(freshDestination)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDestination) } returns documentFile
        val deleted = CompletableDeferred<Unit>()
        val writerFinished = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        every { documentFile.delete() } answers {
            assertTrue("Deletion must not race with active writer on onCleared!", writerFinished.isCompleted)
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

        val onClearedMethod = MainViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)

        // Deletion must NOT occur while writer is still running
        assertFalse(deleted.isCompleted)

        // Allow writer to complete and verify deletion happens cleanly
        allowFinish.complete(Unit)
        withTimeout(5_000) { viewModel.lastCleanupJob?.join() }
        withTimeout(5_000) { deleted.await() }

        assertFalse(OutputPolicy.isFreshCopy(freshDestination))
    }

    @Test
    fun `fresh incomplete destination is not deleted when onCleared writer exceeds join timeout until writer stops`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        viewModel.onClearedWriterJoinTimeoutMs = 100L // shorten join timeout for deterministic test
        val source = Uri.parse("content://fixture/source.pdf")
        val freshDestination = Uri.parse("content://fixture/fresh_copy_timeout_${UUID.randomUUID()}.pdf")

        OutputPolicy.registerFreshCopy(freshDestination)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        mockkStatic(DocumentFile::class)
        val documentFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDestination) } returns documentFile
        val deleted = CompletableDeferred<Unit>()
        val writerFinished = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        every { documentFile.delete() } answers {
            assertTrue("Deletion must not occur before writer has confirmed termination!", writerFinished.isCompleted)
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

        val onClearedMethod = MainViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)

        // Wait for the initial onCleared cleanup job to time out waiting for writer
        withTimeout(2_000) { viewModel.lastCleanupJob?.join() }

        // Writer is still active (allowFinish not completed). Deletion MUST NOT have occurred!
        assertFalse("Destination must not be deleted while writer is still running even after timeout!", deleted.isCompleted)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        // Now allow writer to terminate
        allowFinish.complete(Unit)

        // Deletion must now occur via deferred completion
        withTimeout(5_000) { deleted.await() }
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
    fun `source equals destination is rejected before destructive open and source bytes remain unchanged`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val testBytes = "PDF-TEST-SEED-BYTES-ORIGINAL-DATA".toByteArray(Charsets.UTF_8)
        val seedFile = File(application.cacheDir, "test_source_protection_${UUID.randomUUID()}.pdf").apply {
            writeBytes(testBytes)
        }
        val sourceUri = Uri.fromFile(seedFile)
        try {
            // 1. Direct engine call must fail-closed with SecurityException before opening any stream
            val compressResult = PdfCompressor.compressPdf(application, sourceUri, sourceUri)
            assertTrue("Direct compression must fail when source matches destination", compressResult.isFailure)
            assertTrue("Failure must be SecurityException", compressResult.exceptionOrNull() is SecurityException)
            assertArrayEquals("Source file bytes must remain 100% untouched after rejected direct compression",
                testBytes, seedFile.readBytes())

            // 2. ViewModel entry point must reject before recording pending output or truncating
            val viewModel = MainViewModel(application)
            viewModel.compressPdf(application, sourceUri, sourceUri)

            withTimeout(5_000) {
                viewModel.uiState.first { it is MainViewModel.UiState.Error }
            }

            assertArrayEquals("Source file bytes must remain 100% untouched after ViewModel rejection",
                testBytes, seedFile.readBytes())
        } finally {
            seedFile.delete()
        }
    }

    @Test
    fun `canonical file path alias of source as destination is rejected and source bytes remain unchanged`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val testBytes = "SEED_ALIAS_PROTECTED_BYTES".toByteArray(Charsets.UTF_8)
        val realFile = File(application.cacheDir, "test_alias_${UUID.randomUUID()}.pdf").apply {
            writeBytes(testBytes)
        }
        val sourceUri = Uri.fromFile(realFile)
        val aliasFile = File(realFile.parentFile, "./${realFile.name}")
        val aliasUri = Uri.fromFile(aliasFile)
        try {
            assertTrue("SourcePreservationPolicy must recognize canonical path alias",
                SourcePreservationPolicy.isSameDocument(application, sourceUri, aliasUri))

            val compressResult = PdfCompressor.compressPdf(application, sourceUri, aliasUri)
            assertTrue("Compression must fail on canonical alias destination", compressResult.isFailure)
            assertTrue("Failure must be SecurityException", compressResult.exceptionOrNull() is SecurityException)
            assertArrayEquals("Source file bytes must remain untouched after alias overwrite rejection",
                testBytes, realFile.readBytes())
        } finally {
            realFile.delete()
        }
    }

    @Test
    fun `staged capability provenance mapping rejects destination matching original source`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val sourceFile = File(application.cacheDir, "test_staged_${UUID.randomUUID()}.pdf").apply {
            writeBytes("%PDF-1.4\n%staged provenance test\n%%EOF".toByteArray(Charsets.UTF_8))
        }
        val sourceUri = Uri.fromFile(sourceFile)
        val stagedLease = com.pdfchemy.app.utils.DocumentStager.stageDocumentCancellableWithLease(application, sourceUri)
        try {
            val stagedDocUri = stagedLease.document.uri
            assertTrue("Policy must map staged capability back to original source URI",
                SourcePreservationPolicy.isSameDocument(application, stagedDocUri, sourceUri))
            assertTrue("Policy must be symmetric across staged capability and original source",
                SourcePreservationPolicy.isSameDocument(application, sourceUri, stagedDocUri))
            var exceptionThrown = false
            try {
                SourcePreservationPolicy.requireSeparateDestination(application, stagedDocUri, sourceUri)
            } catch (e: SecurityException) {
                exceptionThrown = true
            }
            assertTrue("requireSeparateDestination must throw SecurityException for staged capability", exceptionThrown)
        } finally {
            stagedLease.lease.close()
            com.pdfchemy.app.utils.DocumentStager.release(stagedLease.document)
            sourceFile.delete()
        }
    }

    @Test
    fun `distinct destination is permitted by source preservation policy`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val sourceFile = File(application.cacheDir, "test_src_${UUID.randomUUID()}.pdf").apply {
            writeBytes("SOURCE_DATA".toByteArray(Charsets.UTF_8))
        }
        val destFile = File(application.cacheDir, "test_dst_${UUID.randomUUID()}.pdf").apply {
            writeBytes("DISTINCT_DATA".toByteArray(Charsets.UTF_8))
        }
        val sourceUri = Uri.fromFile(sourceFile)
        val destUri = Uri.fromFile(destFile)
        try {
            assertFalse("Unrelated distinct files must not be identified as same document",
                SourcePreservationPolicy.isSameDocument(application, sourceUri, destUri))
            SourcePreservationPolicy.requireSeparateDestination(application, sourceUri, destUri)
        } finally {
            sourceFile.delete()
            destFile.delete()
        }
    }

    @Test
    fun `merge operation rejects when any input source matches destination`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val testBytes1 = "MERGE_SRC_1".toByteArray(Charsets.UTF_8)
        val testBytes2 = "MERGE_SRC_2".toByteArray(Charsets.UTF_8)
        val src1 = File(application.cacheDir, "test_merge1_${UUID.randomUUID()}.pdf").apply {
            writeBytes(testBytes1)
        }
        val src2 = File(application.cacheDir, "test_merge2_${UUID.randomUUID()}.pdf").apply {
            writeBytes(testBytes2)
        }
        val src1Uri = Uri.fromFile(src1)
        val src2Uri = Uri.fromFile(src2)
        try {
            val viewModel = MainViewModel(application)
            viewModel.mergePdfs(application, listOf(src1Uri, src2Uri), src1Uri)
            withTimeout(5_000) {
                viewModel.uiState.first { it is MainViewModel.UiState.Error }
            }
            assertArrayEquals("Merge source 1 must remain intact when self-overwrite is attempted",
                testBytes1, src1.readBytes())
            assertArrayEquals("Merge source 2 must remain intact when self-overwrite is attempted",
                testBytes2, src2.readBytes())
        } finally {
            src1.delete()
            src2.delete()
        }
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

    @Test
    fun `direct screen showSuccessToast retires fresh copy status in OutputPolicy`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val freshDestination = Uri.parse("content://fixture/direct_screen_toast_dest.pdf")

        OutputPolicy.registerFreshCopy(freshDestination)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        mockkStatic(DocumentFile::class)
        val docFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDestination) } returns docFile
        every { docFile.delete() } answers {
            fail("Destination published via direct-screen showSuccessToast must NEVER be deleted!")
            true
        }

        // Direct screen finishes writing and notifies success
        viewModel.showSuccessToast("Title", "Saved", freshDestination)

        assertFalse("Fresh copy status must be retired after showSuccessToast publication",
            OutputPolicy.isFreshCopy(freshDestination))

        // Subsequent cancellation or error must not touch the published direct-screen file
        viewModel.cancelOperation(application)
        delay(50)
        verify(exactly = 0) { docFile.delete() }
    }

    @Test
    fun `exportEditedPdf completion retires fresh copy status`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source_edit.pdf")
        val freshDestination = Uri.parse("content://fixture/fresh_edited.pdf")

        OutputPolicy.registerFreshCopy(freshDestination)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        mockkObject(PdfEditor)
        coEvery { PdfEditor.exportModifiedPdf(any(), any(), any(), any()) } returns Result.success(true)

        val completed = CompletableDeferred<Boolean>()
        viewModel.exportEditedPdf(application, source, freshDestination, emptyMap()) {
            completed.complete(it)
        }

        val success = completed.await()
        assertTrue("exportEditedPdf must complete successfully", success)
        assertFalse("Fresh copy status must be retired after exportEditedPdf publication",
            OutputPolicy.isFreshCopy(freshDestination))
    }

    @Test
    fun `compressImage completion retires fresh copy status`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source_img.jpg")
        val freshDestination = Uri.parse("content://fixture/fresh_compressed_img.jpg")

        OutputPolicy.registerFreshCopy(freshDestination)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        mockkObject(ImageCompressor)
        coEvery { ImageCompressor.compressImage(any(), any(), any(), any(), any(), any(), any()) } returns
            ImageCompressionResult(success = true, originalSize = 5000L, compressedSize = 2500L, width = 100, height = 100, format = "JPEG")

        val completed = CompletableDeferred<ImageCompressionResult>()
        viewModel.compressImage(application, source, freshDestination, 75, ImageOutputFormat.JPEG, 0, true) {
            completed.complete(it)
        }

        val result = completed.await()
        assertTrue("compressImage must succeed", result.success)
        assertFalse("Fresh copy status must be retired after compressImage publication",
            OutputPolicy.isFreshCopy(freshDestination))
    }

    @Test
    fun `convertMarkdownToPdf retires fresh copy status on success and deletes on failure`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val freshSuccessDest = Uri.parse("content://fixture/fresh_markdown_success.pdf")
        val freshFailureDest = Uri.parse("content://fixture/fresh_markdown_failure.pdf")

        // 1. Success path
        OutputPolicy.registerFreshCopy(freshSuccessDest)
        assertTrue(OutputPolicy.isFreshCopy(freshSuccessDest))

        mockkObject(MarkdownEngine)
        coEvery { MarkdownEngine.markdownToPdf(any(), any(), freshSuccessDest, any()) } returns Result.success(true)

        viewModel.convertMarkdownToPdf(application, "# Header", freshSuccessDest, "Doc") {}
        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Success }
        }

        assertFalse("Fresh copy status must be retired after successful Markdown export",
            OutputPolicy.isFreshCopy(freshSuccessDest))

        // 2. Failure path deletes fresh incomplete file
        OutputPolicy.registerFreshCopy(freshFailureDest)
        assertTrue(OutputPolicy.isFreshCopy(freshFailureDest))

        mockkStatic(DocumentFile::class)
        val failureDocFile = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshFailureDest) } returns failureDocFile
        var deleteCalled = false
        every { failureDocFile.delete() } answers { deleteCalled = true; true }

        coEvery { MarkdownEngine.markdownToPdf(any(), any(), freshFailureDest, any()) } returns
            Result.failure(IOException("Markdown conversion failed"))

        viewModel.convertMarkdownToPdf(application, "# Header", freshFailureDest, "Doc") {}
        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Error }
        }

        delay(100)
        assertTrue("Incomplete fresh Markdown output must be deleted on failure", deleteCalled)
        verify(atLeast = 1) { failureDocFile.delete() }
    }

    @Test
    fun `OutputPolicy finalizeOutput retires freshness on success and cleans only if fresh on failure`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val freshSuccess = Uri.parse("content://fixture/finalize_fresh_success.pdf")
        val freshFailure = Uri.parse("content://fixture/finalize_fresh_failure.pdf")
        val existingFailure = Uri.parse("content://fixture/finalize_existing_failure.pdf")

        mockkStatic(DocumentFile::class)
        val freshFailDoc = mockk<DocumentFile>()
        val existingFailDoc = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshFailure) } returns freshFailDoc
        every { DocumentFile.fromSingleUri(any(), existingFailure) } returns existingFailDoc
        var freshDeleted = false
        every { freshFailDoc.delete() } answers { freshDeleted = true; true }
        every { existingFailDoc.delete() } answers {
            fail("Existing destination must never be deleted!")
            true
        }

        // 1. Success retires freshness
        OutputPolicy.registerFreshCopy(freshSuccess)
        assertTrue(OutputPolicy.isFreshCopy(freshSuccess))
        OutputPolicy.finalizeOutput(application, freshSuccess, success = true)
        assertFalse(OutputPolicy.isFreshCopy(freshSuccess))

        // 2. Fresh failure deletes incomplete file
        OutputPolicy.registerFreshCopy(freshFailure)
        assertTrue(OutputPolicy.isFreshCopy(freshFailure))
        OutputPolicy.finalizeOutput(application, freshFailure, success = false)
        assertTrue("Fresh incomplete file must be deleted on failure", freshDeleted)
        assertFalse(OutputPolicy.isFreshCopy(freshFailure))

        // 3. Existing failure preserves file
        assertFalse(OutputPolicy.isFreshCopy(existingFailure))
        OutputPolicy.finalizeOutput(application, existingFailure, success = false)
        verify(exactly = 0) { existingFailDoc.delete() }
    }

    @Test
    fun `applyRedactions retains operation ownership and retires fresh copy status on success`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source_redact.pdf")
        val freshDestination = Uri.parse("content://fixture/fresh_redact_success.pdf")

        OutputPolicy.registerFreshCopy(freshDestination)
        assertTrue(OutputPolicy.isFreshCopy(freshDestination))

        mockkObject(com.pdfchemy.app.logic.PdfRedactionEngine)
        coEvery {
            com.pdfchemy.app.logic.PdfRedactionEngine.applyRedactions(any(), source, freshDestination, any(), any())
        } returns Result.success(5)

        val completed = CompletableDeferred<Boolean>()
        viewModel.applyRedactions(application, source, freshDestination, emptyList()) {
            completed.complete(it)
        }

        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Success }
        }

        val success = completed.await()
        assertTrue("Callback must receive success", success)
        assertFalse("Fresh copy status must be retired after applyRedactions success",
            OutputPolicy.isFreshCopy(freshDestination))
    }

    @Test
    fun `applyRedactions cleans up fresh destination on engine failure without deleting existing destination`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source_redact.pdf")
        val freshDest = Uri.parse("content://fixture/fresh_redact_fail.pdf")
        val existingDest = Uri.parse("content://fixture/existing_redact_fail.pdf")

        mockkStatic(DocumentFile::class)
        val freshDoc = mockk<DocumentFile>()
        val existingDoc = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDest) } returns freshDoc
        every { DocumentFile.fromSingleUri(any(), existingDest) } returns existingDoc

        var freshDeleted = false
        every { freshDoc.delete() } answers { freshDeleted = true; true }
        every { existingDoc.delete() } answers {
            fail("Existing destination must never be deleted!")
            true
        }

        mockkObject(com.pdfchemy.app.logic.PdfRedactionEngine)
        coEvery {
            com.pdfchemy.app.logic.PdfRedactionEngine.applyRedactions(any(), any(), any(), any(), any())
        } returns Result.failure(IOException("Redaction engine failed"))

        // 1. Fresh destination failure
        OutputPolicy.registerFreshCopy(freshDest)
        assertTrue(OutputPolicy.isFreshCopy(freshDest))

        val completed1 = CompletableDeferred<Boolean>()
        viewModel.applyRedactions(application, source, freshDest, emptyList()) {
            completed1.complete(it)
        }

        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Error }
        }
        assertFalse("Callback must receive false on failure", completed1.await())
        delay(100)
        assertTrue("Fresh incomplete destination must be deleted on failure", freshDeleted)
        assertFalse(OutputPolicy.isFreshCopy(freshDest))

        // 2. Existing destination failure
        assertFalse(OutputPolicy.isFreshCopy(existingDest))
        val completed2 = CompletableDeferred<Boolean>()
        viewModel.applyRedactions(application, source, existingDest, emptyList()) {
            completed2.complete(it)
        }

        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Error }
        }
        assertFalse("Callback must receive false on failure", completed2.await())
        verify(exactly = 0) { existingDoc.delete() }
    }

    @Test
    fun `applyRedactions rejects same-document destination before processing`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/same_doc.pdf")

        mockkObject(com.pdfchemy.app.logic.PdfRedactionEngine)

        val completed = CompletableDeferred<Boolean>()
        viewModel.applyRedactions(application, source, source, emptyList()) {
            completed.complete(it)
        }

        withTimeout(5_000) {
            viewModel.uiState.first { it is MainViewModel.UiState.Error }
        }

        assertFalse("Callback must receive false when rejected", completed.await())
        coVerify(exactly = 0) {
            com.pdfchemy.app.logic.PdfRedactionEngine.applyRedactions(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `applyRedactions cleans up fresh destination on cancelOperation`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val source = Uri.parse("content://fixture/source_redact.pdf")
        val freshDest = Uri.parse("content://fixture/fresh_redact_cancel.pdf")

        mockkStatic(DocumentFile::class)
        val freshDoc = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), freshDest) } returns freshDoc
        var freshDeleted = false
        every { freshDoc.delete() } answers { freshDeleted = true; true }

        OutputPolicy.registerFreshCopy(freshDest)
        assertTrue(OutputPolicy.isFreshCopy(freshDest))

        val started = CompletableDeferred<Unit>()
        val releaseEngine = CompletableDeferred<Unit>()

        mockkObject(com.pdfchemy.app.logic.PdfRedactionEngine)
        coEvery {
            com.pdfchemy.app.logic.PdfRedactionEngine.applyRedactions(any(), source, freshDest, any(), any())
        } coAnswers {
            started.complete(Unit)
            releaseEngine.await()
            Result.success(1)
        }

        viewModel.applyRedactions(application, source, freshDest, emptyList())
        started.await()

        viewModel.cancelOperation(application)
        releaseEngine.complete(Unit)

        delay(150)
        assertTrue("Fresh destination must be cleaned up on cancelOperation", freshDeleted)
        assertFalse(OutputPolicy.isFreshCopy(freshDest))
    }
}

