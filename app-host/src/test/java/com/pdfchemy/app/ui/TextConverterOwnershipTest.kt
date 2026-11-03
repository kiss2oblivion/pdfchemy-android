package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.TextFormatConverter
import com.pdfchemy.app.logic.TextOutputPublisher
import com.pdfchemy.app.ui.textconverter.TextConverterViewModel
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextConverterOwnershipTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var file: File
    private lateinit var uri: Uri
    private lateinit var vm: TextConverterViewModel
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        file = File.createTempFile("text-owned-", ".txt", context.cacheDir)
        uri = Uri.fromFile(file); OutputPolicy.registerFreshCopy(uri)
        mockkObject(TextFormatConverter, TextOutputPublisher)
        coEvery { TextFormatConverter.convert(any(), any(), any(), any()) } returns Result.success("converted")
        coEvery { TextOutputPublisher.publish(any(), any(), any()) } coAnswers {
            val text = thirdArg<suspend () -> String>().invoke()
            file.writeText(text); OutputPolicy.markPublished(uri)
        }
        vm = TextConverterViewModel()
    }
    @After fun cleanup() {
        if (OutputPolicy.isFreshCopy(uri)) OutputPolicy.unregisterFreshCopy(uri)
        file.delete(); unmockkAll(); Dispatchers.resetMain()
    }
    private fun done() = runBlocking { withTimeout(5_000) { vm.uiState.first { it !is TextConverterViewModel.UiState.Processing } } }

    @Test fun partialWriteFailureDeletesOwnedCopyBeforeReportingError() {
        coEvery { TextOutputPublisher.publish(any(), any(), any()) } coAnswers {
            file.writeText("conve"); throw IOException("controlled full provider")
        }
        vm.convertAndSave(context, uri); done()
        assertTrue(vm.uiState.value is TextConverterViewModel.UiState.Error)
        assertFalse(file.exists()); assertFalse(OutputPolicy.isFreshCopy(uri))
    }
    @Test fun conversionFailureDeletesNeverWrittenFreshCopy() {
        coEvery { TextFormatConverter.convert(any(), any(), any(), any()) } returns Result.failure(IOException("controlled converter failure"))
        vm.convertAndSave(context, uri); done()
        assertTrue(vm.uiState.value is TextConverterViewModel.UiState.Error)
        assertFalse(file.exists()); assertFalse(OutputPolicy.isFreshCopy(uri))
    }
    @Test fun existingUnknownDestinationIsPreservedWithoutCallingPublisher() {
        OutputPolicy.markPublished(uri); file.writeText("existing bytes")
        vm.convertAndSave(context, uri); done()
        assertEquals("existing bytes", file.readText())
        assertTrue(vm.uiState.value is TextConverterViewModel.UiState.Error)
        coVerify(exactly = 0) { TextOutputPublisher.publish(any(), any(), any()) }
    }
    @Test fun cancellationCleansFreshCopyAndNeverReportsSuccessOrError() {
        coEvery { TextFormatConverter.convert(any(), any(), any(), any()) } throws CancellationException("controlled cancellation")
        vm.convertAndSave(context, uri); done()
        assertEquals(TextConverterViewModel.UiState.Idle, vm.uiState.value)
        assertFalse(file.exists()); assertFalse(OutputPolicy.isFreshCopy(uri))
    }
    @Test fun duplicateCallbackCannotDeleteRunningOrPublishedCopy() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { TextOutputPublisher.publish(any(), any(), any()) } coAnswers {
            entered.complete(Unit); release.await()
            file.writeText("converted"); OutputPolicy.markPublished(uri)
        }
        vm.convertAndSave(context, uri); entered.await()
        vm.convertAndSave(context, uri)
        assertTrue(file.exists()); assertTrue(vm.uiState.value is TextConverterViewModel.UiState.Processing)
        release.complete(Unit); done()
        assertEquals("converted", file.readText()); assertFalse(OutputPolicy.isFreshCopy(uri))
        assertFalse(OutputPolicy.deleteIfFresh(context, uri))
        coVerify(exactly = 1) { TextOutputPublisher.publish(any(), any(), any()) }
    }
}
