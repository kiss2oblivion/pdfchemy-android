package com.pdfchemy.app.security

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.OutputDestination
import com.pdfchemy.app.logic.DestinationProvenance
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.ui.MainViewModel
import io.mockk.*
import kotlinx.coroutines.*
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

/** Audit probes assert observed defects, not the desired secure behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IndependentSecurityReviewProbeTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain(); unmockkAll() }

    @Test fun publishedBatchOutputIsDeletedOnCancellation_probe() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(application)
        val uri = Uri.parse("content://audit/published_batch_output.pdf")
        val deleted = CompletableDeferred<Unit>()
        mockkStatic(DocumentFile::class)
        val document = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), uri) } returns document
        every { document.delete() } answers { deleted.complete(Unit); true }
        OutputPolicy.registerFreshCopy(uri)
        vm.addPendingOutput(OutputDestination(uri, DestinationProvenance.FRESH_COPY))
        vm.markOutputPublished(uri)
        assertFalse(OutputPolicy.isFreshCopy(uri))
        vm.cancelOperation(application)
        withTimeout(5000) { deleted.await() }
        verify(exactly = 1) { document.delete() }
    }

    @Test fun directScreenSuccessLeavesPublishedUriClassifiedFresh_probe() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(application)
        val uri = Uri.parse("content://audit/direct_screen_output.pdf")
        OutputPolicy.registerFreshCopy(uri)
        try {
            // Same completion entry point used by RedactionScreen/MarkdownStudioScreen.
            vm.showSuccessToast("Saved", "Saved successfully", uri)
            assertTrue("Observed bug: successful direct-screen output retains freshness", OutputPolicy.isFreshCopy(uri))
        } finally { OutputPolicy.unregisterFreshCopy(uri) }
    }
}
