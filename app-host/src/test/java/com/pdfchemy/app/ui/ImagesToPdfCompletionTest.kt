package com.pdfchemy.app.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.billing.AdManager
import com.pdfchemy.app.logic.PdfGateway
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImagesToPdfCompletionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var app: Application
    private lateinit var destination: File
    private lateinit var output: Uri
    private val inputs = listOf(Uri.parse("content://fixture/image.jpg"))

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        AdManager.resetForTesting()
        mockkObject(PdfGateway)
        app = ApplicationProvider.getApplicationContext()
        destination = File.createTempFile("existing-image-pdf-", ".pdf", app.cacheDir)
        destination.writeText("existing destination bytes")
        output = Uri.fromFile(destination)
    }

    @After
    fun cleanup() {
        destination.delete()
        AdManager.resetForTesting()
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun actualProducerCompletionExposesOutputAndRecordsExactlyOneOpportunity() = runTest(dispatcher) {
        val published = "%PDF-synthetic-gateway-output".toByteArray()
        coEvery { PdfGateway.executeEngineBatch(app, "IMAGES_TO_PDF", inputs, listOf(output), "{}") } coAnswers {
            destination.writeBytes(published)
            mockk(relaxed = true)
        }
        val vm = MainViewModel(app)

        vm.convertImagesToPdf(app, inputs, output)
        testScheduler.advanceUntilIdle()

        assertArrayEquals(published, destination.readBytes())
        val success = vm.uiState.value as MainViewModel.UiState.Success
        assertEquals(listOf(output), success.outputUris)
        assertEquals(listOf(output), vm.consumeAdOpportunity()?.outputUris)
        assertNull(vm.consumeAdOpportunity())
        assertEquals(1L, AdManager.debugCounters.operationComplete)
        coVerify(exactly = 1) { PdfGateway.executeEngineBatch(app, "IMAGES_TO_PDF", inputs, listOf(output), "{}") }
    }

    @Test
    fun gatewayFailureDoesNotOfferOutputOrAdOpportunity() = runTest(dispatcher) {
        val original = destination.readBytes()
        coEvery { PdfGateway.executeEngineBatch(any(), any(), any(), any(), any()) } throws IOException("provider failed")
        val vm = MainViewModel(app)

        vm.convertImagesToPdf(app, inputs, output)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value is MainViewModel.UiState.Error)
        assertNull(vm.consumeAdOpportunity())
        assertEquals(0L, AdManager.debugCounters.operationComplete)
        assertArrayEquals(original, destination.readBytes())
    }

    @Test
    fun cancellationBeforeGatewayCompletionDoesNotOfferOutputOrAdOpportunity() = runTest(dispatcher) {
        val original = destination.readBytes()
        coEvery { PdfGateway.executeEngineBatch(any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        val vm = MainViewModel(app)
        vm.convertImagesToPdf(app, inputs, output)
        testScheduler.runCurrent()
        val job = requireNotNull(vm.activeJobRef)
        assertTrue(vm.uiState.value is MainViewModel.UiState.Processing)

        vm.cancelOperation(app)
        testScheduler.advanceUntilIdle()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(MainViewModel.UiState.Idle, vm.uiState.value)
        assertNull(vm.consumeAdOpportunity())
        assertEquals(0L, AdManager.debugCounters.operationComplete)
        assertArrayEquals(original, destination.readBytes())
    }

    @Test
    fun oversizedImageBatchFailsBeforeGatewayWithoutOutputOrAdvertising() = runTest(dispatcher) {
        val original = destination.readBytes()
        coEvery { PdfGateway.executeEngineBatch(any(), any(), any(), any(), any()) } throws IOException("Batch crossed gateway")
        val vm = MainViewModel(app)
        vm.convertImagesToPdf(app, List(17) { Uri.parse("content://fixture/image-" + it + ".jpg") }, output)
        testScheduler.advanceUntilIdle()

        val error = vm.uiState.value as? MainViewModel.UiState.Error
        assertNotNull("Oversized input must be a normal visible failure", error)
        assertTrue(error.toString(), error!!.message.contains("at most 16"))
        coVerify(exactly = 0) { PdfGateway.executeEngineBatch(any(), any(), any(), any(), any()) }
        assertNull(vm.consumeAdOpportunity())
        assertEquals(0L, AdManager.debugCounters.operationComplete)
        assertArrayEquals(original, destination.readBytes())
    }

}
