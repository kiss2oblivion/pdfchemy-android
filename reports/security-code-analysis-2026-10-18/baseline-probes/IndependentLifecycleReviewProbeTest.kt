package com.pdfchemy.app.security

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.PdfCompressor
import com.pdfchemy.app.ui.MainViewModel
import com.pdfchemy.app.utils.DocumentStager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** Audit probes deliberately assert vulnerable behavior at the reviewed revision. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IndependentLifecycleReviewProbeTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain(); unmockkAll() }

    @Test fun completedTargetMissedOutputIsDeletedOnViewModelClear_probe() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(application)
        val source = Uri.parse("content://audit/source.pdf")
        val uri = Uri.parse("content://audit/completed_warning.pdf")
        val deleted = CompletableDeferred<Unit>()
        OutputPolicy.registerFreshCopy(uri)
        mockkStatic(DocumentFile::class)
        val document = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), uri) } returns document
        every { document.delete() } answers { deleted.complete(Unit); true }
        mockkObject(PdfCompressor)
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(PdfCompressor.CompressionReport(1000L, 0, false, true))
        vm.setTargetMb(0.001f)
        vm.compressPdf(application, source, uri)
        withTimeout(5000) { vm.uiState.first { it is MainViewModel.UiState.Warning } }
        assertTrue(OutputPolicy.isFreshCopy(uri))
        MainViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)
        withTimeout(5000) { deleted.await() }
        verify(exactly = 1) { document.delete() }
    }

    @Test fun sourceEqualsDestinationReachesCompressorInsteadOfBeingRejected_probe() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(application)
        val same = Uri.parse("content://audit/original.pdf")
        mockkObject(PdfCompressor)
        coEvery { PdfCompressor.compressPdf(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(PdfCompressor.CompressionReport(1000L, 0, false, false))
        vm.compressPdf(application, same, same)
        withTimeout(5000) { vm.uiState.first { it is MainViewModel.UiState.Success } }
        coVerify(exactly = 1) { PdfCompressor.compressPdf(application, same, same, any(), any(), any(), any(), any()) }
    }

    @Test fun outerDispatcherCancellationLosesAtomicallyCreatedStagingLease_probe() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ready = CompletableDeferred<DocumentStager.StagedLease>()
        var reachedCaller = false
        val caller = scope.launch {
            // The same acquisition boundary used by Reader/Editor/Reflow.
            val owned = withContext(Dispatchers.IO) {
                DocumentStager.stageStreamCancellableWithLease(application,
                    ByteArrayInputStream("audit snapshot".toByteArray())).also { ready.complete(it) }
            }
            try { reachedCaller = true } finally { owned.close(); DocumentStager.release(owned.document) }
        }
        try {
            scheduler.runCurrent()
            val acquired = withTimeout(10000) { ready.await() }
            try {
                caller.cancel()
                scheduler.runCurrent()
                caller.join()
                assertFalse(reachedCaller)
                assertEquals("Observed bug: cancelled UI caller has no handle to release this lease", 1,
                    DocumentStager.activeLeases(acquired.document))
            } finally { acquired.close(); DocumentStager.release(acquired.document) }
        } finally { scope.cancel() }
    }
}
