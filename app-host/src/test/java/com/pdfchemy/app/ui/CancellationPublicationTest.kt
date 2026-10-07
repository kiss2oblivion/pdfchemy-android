package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.PdfManipulator
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.logic.StandardOutputContract
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.DataOutputStream
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CancellationPublicationTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain(); unmockkAll() }

    @Test fun cancelledWriterStopsBeforeDestinationIsDeletedAndCannotReplaceIdleWithError() = runBlocking<Unit> {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val output = Uri.parse("content://fixture/output.pdf")
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()
        val deleted = CompletableDeferred<Unit>()
        mockkObject(PdfManipulator)
        mockkStatic(DocumentFile::class)
        val document = mockk<DocumentFile>()
        every { DocumentFile.fromSingleUri(any(), output) } returns document
        every { document.delete() } answers {
            assertTrue("Deletion raced a writer", finished.isCompleted)
            deleted.complete(Unit)
            true
        }
        coEvery { PdfManipulator.mergePdfs(any(), any(), output) } coAnswers {
            started.complete(Unit)
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) { allowFinish.await() }
                finished.complete(Unit)
            }
        }
        viewModel.mergePdfs(application, listOf(Uri.parse("content://fixture/source.pdf")), output)
        started.await()
        viewModel.cancelOperation(application)
        assertSame(MainViewModel.UiState.Idle, viewModel.uiState.value)
        assertFalse(deleted.isCompleted)
        allowFinish.complete(Unit)
        withTimeout(5_000) { deleted.await() }
        assertSame(MainViewModel.UiState.Idle, viewModel.uiState.value)
    }

    @Test fun imageExtractionFailureDeletesPartialOutputsReportsFailureAndReleasesFrames() = runBlocking<Unit> {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(application)
        val context = mockk<Context>(relaxed = true)
        val resolver = mockk<ContentResolver>(relaxed = true)
        every { context.cacheDir } returns application.cacheDir
        every { context.contentResolver } returns resolver
        val directory = mockk<DocumentFile>()
        val document = mockk<DocumentFile>(relaxed = true)
        val destination = Uri.parse("content://fixture/image.jpg")
        every { document.uri } returns destination
        every { directory.createFile(any(), any()) } returns document
        every { resolver.openOutputStream(destination, "wt") } returns null
        val callback = CompletableDeferred<Pair<Int, Int>>()
        val deleted = CompletableDeferred<Unit>()
        every { document.delete() } answers { deleted.complete(Unit); true }
        var frames: File? = null
        mockkObject(PdfGateway)
        coEvery { PdfGateway.executeEngine(any(), "IMAGE_EXTRACT_FRAMED", any(), any(), any()) } coAnswers {
            frames = File(arg<Uri>(3).path!!)
            DataOutputStream(frames!!.outputStream()).use { it.writeInt(4); it.write(byteArrayOf(1, 2, 3, 4)); it.writeInt(-1) }
            StandardOutputContract()
        }
        viewModel.extractImagesFromPdf(Uri.parse("content://fixture/input.pdf"), directory, context) { count, errors -> callback.complete(count to errors) }
        assertEquals(0 to 1, withTimeout(5_000) { callback.await() })
        // onComplete runs before the error state and finally cleanup; observing the
        // frames disappearing also precedes deletion of the published destination.
        withTimeout(5_000) { deleted.await() }
        withTimeout(5_000) { viewModel.uiState.first { it is MainViewModel.UiState.Error } }
        assertFalse("Temporary frames must be released before output cleanup completes", frames!!.exists())
        verify { document.delete() }
        assertTrue(viewModel.uiState.value is MainViewModel.UiState.Error)
    }
}
