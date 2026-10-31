package com.pdfchemy.app.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.OfficeExportEngine
import com.pdfchemy.app.logic.OfficeExportReport
import com.pdfchemy.app.logic.OfficeFormat
import com.pdfchemy.app.logic.PdfCompressor
import com.pdfchemy.app.logic.PdfManipulator
import com.pdfchemy.app.logic.PdfTextExtractor
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OperationConcurrencyTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun secondaryOperationsAreRejectedWhilePrimaryOperationIsProcessing() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        val sourceUri = Uri.parse("content://fixture/source.pdf")
        val primaryDestUri = Uri.parse("content://fixture/primary_compressed.pdf")
        val secondaryDest1 = Uri.parse("content://fixture/secondary_rotated.pdf")
        val secondaryDest2 = Uri.parse("content://fixture/secondary_extracted.txt")
        val secondaryDest3 = Uri.parse("content://fixture/secondary_office.docx")

        mockkObject(PdfCompressor)
        mockkObject(PdfManipulator)
        mockkObject(PdfTextExtractor)
        mockkObject(OfficeExportEngine)

        val primaryPaused = CompletableDeferred<Unit>()
        val releasePrimary = CompletableDeferred<Unit>()

        coEvery {
            PdfCompressor.compressPdf(
                context = any(),
                sourceUri = any(),
                destUri = any(),
                quality = any(),
                useGrayscale = any(),
                useLossless = any(),
                stripMetadata = any(),
                targetMb = any()
            )
        } coAnswers {
            primaryPaused.complete(Unit)
            releasePrimary.await()
            Result.success(
                PdfCompressor.CompressionReport(
                    originalSize = 1000L,
                    imagesProcessed = 1,
                    hasSignatures = false,
                    targetMissed = false
                )
            )
        }

        // 1. Launch primary operation
        vm.compressPdf(app, sourceUri, primaryDestUri)
        testScheduler.advanceUntilIdle()

        // Verify primary operation is running
        assertTrue("Primary operation must set isProcessingActive to true", vm.isProcessingActive())
        val primaryOpId = vm.currentOpId
        assertNotEquals("Primary operation ID must be non-zero", 0L, primaryOpId)
        val primaryJob = vm.activeJobRef
        assertNotNull("Primary activeJob must not be null", primaryJob)
        assertTrue("Primary activeJob must be active", primaryJob?.isActive == true)

        val primaryOutputsBefore = vm.getActiveOperationOutputs(primaryOpId)
        assertTrue("Primary outputs must contain primaryDestUri", primaryOutputsBefore.contains(primaryDestUri))
        assertEquals("Primary outputs must contain exactly 1 output", 1, primaryOutputsBefore.size)

        // 2. Attempt secondary operation 1: rotatePdf
        vm.rotatePdf(app, sourceUri, secondaryDest1, 90)

        // 3. Attempt secondary operation 2: extractTextFromPdf
        vm.extractTextFromPdf(app, sourceUri, secondaryDest2)

        // 4. Attempt secondary operation 3: exportPdfToOffice (with completion callback)
        var exportCompleted = false
        var exportSuccess = true
        vm.exportPdfToOffice(app, sourceUri, secondaryDest3, OfficeFormat.WORD) { success ->
            exportCompleted = true
            exportSuccess = success
        }

        // Verify secondary operations were rejected immediately
        assertTrue("exportPdfToOffice onReject callback must be invoked", exportCompleted)
        assertFalse("exportPdfToOffice callback must report false (rejected)", exportSuccess)

        // Verify secondary engines were NEVER called
        coVerify(exactly = 0) { PdfManipulator.rotatePdf(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { PdfTextExtractor.extractText(any(), any(), any()) }
        coVerify(exactly = 0) { OfficeExportEngine.exportToWord(any(), any(), any()) }

        // Verify primary state was NOT corrupted / displaced
        assertEquals("currentOpId must not be overwritten by rejected operations", primaryOpId, vm.currentOpId)
        assertSame("activeJob must not be overwritten by rejected operations", primaryJob, vm.activeJobRef)

        val primaryOutputsAfter = vm.getActiveOperationOutputs(primaryOpId)
        assertEquals("No secondary output destinations may be attached to primary operation", 1, primaryOutputsAfter.size)
        assertTrue("Primary output must remain intact", primaryOutputsAfter.contains(primaryDestUri))
        assertFalse("Secondary dest 1 must not be in active outputs", primaryOutputsAfter.contains(secondaryDest1))
        assertFalse("Secondary dest 2 must not be in active outputs", primaryOutputsAfter.contains(secondaryDest2))
        assertFalse("Secondary dest 3 must not be in active outputs", primaryOutputsAfter.contains(secondaryDest3))

        // 5. Cleanly finish primary operation
        releasePrimary.complete(Unit)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun cancellingActiveOperationOnlyCancelsPrimaryJobAndPreservesSecondaryDestinations() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        val sourceUri = Uri.parse("content://fixture/source.pdf")
        val primaryDest = Uri.parse("content://fixture/primary_dest.pdf")
        val secondaryDest = Uri.parse("content://fixture/secondary_dest.pdf")

        mockkObject(PdfCompressor)
        mockkObject(PdfManipulator)

        val primaryStarted = CompletableDeferred<Unit>()
        val primaryCancelled = CompletableDeferred<Unit>()

        coEvery {
            PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            primaryStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                primaryCancelled.complete(Unit)
            }
        }

        // Start primary operation
        vm.compressPdf(app, sourceUri, primaryDest)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.isProcessingActive())
        val primaryOpId = vm.currentOpId
        val primaryJob = vm.activeJobRef

        // Attempt secondary operation while primary is processing
        vm.rotatePdf(app, sourceUri, secondaryDest, 90)

        // Cancel active operation
        vm.cancelOperation(app)
        testScheduler.advanceUntilIdle()

        // Assert primary job was cancelled
        assertTrue("Primary job must be cancelled", primaryJob?.isCancelled == true)
        assertEquals("currentOperationId must be reset to 0", 0L, vm.currentOpId)
        assertNull("activeJob must be cleared", vm.activeJobRef)
        assertEquals("UiState must return to Idle", MainViewModel.UiState.Idle, vm.uiState.value)

        // Secondary destination must NEVER have been registered in active operations
        val outputsForPrimary = vm.getActiveOperationOutputs(primaryOpId)
        assertTrue("Active operation outputs must be empty after cancellation", outputsForPrimary.isEmpty())
        coVerify(exactly = 0) { PdfManipulator.rotatePdf(any(), any(), any(), any(), any()) }
    }

    @Test
    fun concurrentAdmissionAllowsExactlyOneWinner() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        mockkObject(OfficeExportEngine)
        val releaseExport = CompletableDeferred<Unit>()
        val admittedCount = AtomicInteger(0)
        val rejectedCount = AtomicInteger(0)

        coEvery { OfficeExportEngine.exportToWord(any(), any(), any()) } coAnswers {
            admittedCount.incrementAndGet()
            releaseExport.await()
            Result.success(
                OfficeExportReport(
                    format = OfficeFormat.WORD,
                    pageCount = 1,
                    outputSizeBytes = 100L,
                    itemsExtracted = 0
                )
            )
        }

        val threadCount = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            val destUri = Uri.parse("content://fixture/dest_$i.docx")
            executor.submit {
                startGate.await()
                try {
                    vm.exportPdfToOffice(
                        app,
                        Uri.parse("content://fixture/src_$i.pdf"),
                        destUri,
                        OfficeFormat.WORD
                    ) { success ->
                        // onComplete callback with false means rejected by tryAdmitOperation
                        if (!success) {
                            rejectedCount.incrementAndGet()
                        }
                    }
                } finally {
                    doneGate.countDown()
                }
            }
        }

        startGate.countDown()
        assertTrue("All threads must submit within 5 seconds", doneGate.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        // Since UiState was set to Processing by the first admitted operation under lock,
        // exactly 19 operations must have been immediately rejected on the caller thread.
        assertEquals("All other 19 operations must be rejected immediately", threadCount - 1, rejectedCount.get())

        releaseExport.complete(Unit)
    }

    @Test
    fun warningResultMarksOutputPublishedAndClearsActiveJob() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        val sourceUri = Uri.parse("content://fixture/source.pdf")
        val destUri = Uri.parse("content://fixture/warning_compressed.pdf")

        mockkObject(PdfCompressor)
        coEvery {
            PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(
            PdfCompressor.CompressionReport(
                originalSize = 2000L,
                imagesProcessed = 1,
                hasSignatures = false,
                targetMissed = true // Triggers UiState.Warning
            )
        )

        vm.compressPdf(app, sourceUri, destUri)
        testScheduler.advanceUntilIdle()

        // UiState must be Warning
        val state = vm.uiState.value
        assertTrue("State must be UiState.Warning when target is missed", state is MainViewModel.UiState.Warning)
        val warning = state as MainViewModel.UiState.Warning
        assertTrue("Warning outputUris must contain destUri", warning.outputUris.contains(destUri))

        // Active job and current operation must be cleanly cleared
        assertFalse("isProcessingActive must be false after warning terminal state", vm.isProcessingActive())
        assertEquals("currentOperationId must be reset to 0L", 0L, vm.currentOpId)
        assertNull("activeJob must be cleared", vm.activeJobRef)

        // Output destination must be marked published in OutputPolicy
        assertFalse("Published output must not be fresh copy", com.pdfchemy.app.logic.OutputPolicy.isFreshCopy(destUri))

        // Subsequent operation can be admitted immediately
        mockkObject(PdfManipulator)
        val secondDest = Uri.parse("content://fixture/rotated.pdf")
        coEvery { PdfManipulator.rotatePdf(any(), any(), any(), any(), any()) } just Runs

        vm.rotatePdf(app, sourceUri, secondDest, 90)
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { PdfManipulator.rotatePdf(any(), any(), any(), any(), any()) }
    }

    @Test
    fun dismissErrorDoesNotDisruptActiveProcessing() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        val sourceUri = Uri.parse("content://fixture/source.pdf")
        val destUri = Uri.parse("content://fixture/active_dest.pdf")

        mockkObject(PdfCompressor)
        val releaseCompress = CompletableDeferred<Unit>()
        coEvery {
            PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            releaseCompress.await()
            Result.success(PdfCompressor.CompressionReport(1000L, 1, false, false))
        }

        vm.compressPdf(app, sourceUri, destUri)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.isProcessingActive())
        val jobBefore = vm.activeJobRef
        assertNotNull(jobBefore)

        // Calling dismissError while Processing must be a no-op
        vm.dismissError()
        testScheduler.advanceUntilIdle()

        assertTrue("isProcessingActive must still be true after dismissError", vm.isProcessingActive())
        assertSame("activeJob must not be cleared by dismissError", jobBefore, vm.activeJobRef)
        assertTrue("UiState must remain Processing", vm.uiState.value is MainViewModel.UiState.Processing)

        // Cleanly finish operation
        releaseCompress.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value is MainViewModel.UiState.Success)

        // Now simulate Error state and test dismissError transitions to Idle
        vm.notifyError("Test error", "Details")
        assertTrue(vm.uiState.value is MainViewModel.UiState.Error)
        vm.dismissError()
        assertEquals(MainViewModel.UiState.Idle, vm.uiState.value)
    }

    @Test
    fun resetStateCoordinatesCleanlyWithActiveOperation() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        val sourceUri = Uri.parse("content://fixture/source.pdf")
        val destUri = Uri.parse("content://fixture/reset_dest.pdf")

        mockkObject(PdfCompressor)
        val jobCancelled = CompletableDeferred<Unit>()
        coEvery {
            PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            try {
                awaitCancellation()
            } finally {
                jobCancelled.complete(Unit)
            }
        }

        vm.compressPdf(app, sourceUri, destUri)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.isProcessingActive())
        val job = vm.activeJobRef

        // Reset state while active
        vm.resetState()
        testScheduler.advanceUntilIdle()

        assertTrue("Active job must be cancelled by resetState", job?.isCancelled == true)
        assertFalse("isProcessingActive must be false", vm.isProcessingActive())
        assertNull("activeJob must be cleared", vm.activeJobRef)
        assertEquals("UiState must be Idle", MainViewModel.UiState.Idle, vm.uiState.value)
        assertEquals("Selected files must be cleared", emptyList<MainViewModel.SelectedFile>(), vm.selectedFiles.value)
    }

    @Test
    fun cancellationAndFailurePreservePreExistingDestinationBytes() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        // Create an actual pre-existing file on disk with known bytes
        val preExistingFile = java.io.File(app.cacheDir, "pre_existing_document.pdf")
        val originalBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x37) // "%PDF-1.7"
        preExistingFile.writeBytes(originalBytes)
        val preExistingUri = Uri.fromFile(preExistingFile)

        // Create an uncommitted fresh copy file on disk
        val freshCopyFile = java.io.File(app.cacheDir, "fresh_copy_to_delete.pdf")
        val freshBytes = byteArrayOf(0x01, 0x02, 0x03)
        freshCopyFile.writeBytes(freshBytes)
        val freshUri = Uri.fromFile(freshCopyFile)
        com.pdfchemy.app.logic.OutputPolicy.registerFreshCopy(freshUri)

        mockkObject(PdfCompressor)
        coEvery {
            PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            awaitCancellation()
        }

        // Start operation using the pre-existing URI
        vm.compressPdf(app, Uri.parse("content://fixture/source.pdf"), preExistingUri)
        testScheduler.advanceUntilIdle()

        // Also register fresh copy under the active operation to verify differential cleanup
        vm.addPendingOutputUri(freshUri, isFreshCopy = true)

        // Verify pre-existing file is NOT registered as fresh copy
        assertFalse("Pre-existing destination must not be marked fresh copy", com.pdfchemy.app.logic.OutputPolicy.isFreshCopy(preExistingUri))
        assertTrue("Fresh copy must be marked fresh copy", com.pdfchemy.app.logic.OutputPolicy.isFreshCopy(freshUri))

        // Cancel the operation
        vm.cancelOperation(app)
        testScheduler.advanceUntilIdle()

        // Wait for background IO cleanup coroutine
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            while (freshCopyFile.exists() && System.currentTimeMillis() - start < 3000) {
                Thread.sleep(25)
            }
        }

        // Verification: Pre-existing file MUST still exist with identical bytes!
        assertTrue("Pre-existing file must NOT be deleted upon cancellation", preExistingFile.exists())
        assertArrayEquals("Pre-existing bytes must remain intact", originalBytes, preExistingFile.readBytes())

        // Verification: Fresh copy MUST be deleted upon cancellation
        assertFalse("Uncommitted fresh copy MUST be deleted upon cancellation", freshCopyFile.exists())
        assertFalse("Fresh copy must be unregistered from OutputPolicy", com.pdfchemy.app.logic.OutputPolicy.isFreshCopy(freshUri))

        // Cleanup
        if (preExistingFile.exists()) preExistingFile.delete()
    }
}
