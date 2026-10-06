package com.pdfchemy.app.ui

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.TextFormatConverter
import com.pdfchemy.app.ui.textconverter.TextConverterViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextConverterPublicationTest {
    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val destination = Uri.parse("content://fixture/output.txt")
    private lateinit var viewModel: TextConverterViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.contentResolver } returns resolver
        mockkObject(TextFormatConverter)
        coEvery { TextFormatConverter.convert(any(), any(), any(), any()) } returns Result.success("converted text")
        viewModel = TextConverterViewModel()
    }
    @After fun cleanup() { Dispatchers.resetMain(); unmockkAll() }
    private fun awaitCompletion() = runBlocking {
        kotlinx.coroutines.withTimeout(5_000) {
            while (viewModel.uiState.value == TextConverterViewModel.UiState.Processing) delay(10)
        }
    }

    @Test fun nullProviderStreamIsFailureNotSuccess() {
        every { resolver.openOutputStream(destination, "wt") } returns null
        viewModel.convertAndSave(context, destination)
        awaitCompletion()
        assertTrue(viewModel.uiState.value is TextConverterViewModel.UiState.Error)
    }
    @Test fun writeFailureNeverReportsSuccess() {
        every { resolver.openOutputStream(destination, "wt") } returns object : OutputStream() {
            override fun write(value: Int) { throw IOException("provider full") }
        }
        viewModel.convertAndSave(context, destination)
        awaitCompletion()
        assertTrue(viewModel.uiState.value is TextConverterViewModel.UiState.Error)
    }
    @Test fun successRequiresTheActualUtf8BytesToBeWrittenAndClosed() {
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        every { resolver.openOutputStream(destination, "wt") } returns output
        viewModel.convertAndSave(context, destination)
        awaitCompletion()
        assertEquals("converted text", output.toString("UTF-8"))
        assertTrue(closed)
        assertTrue(viewModel.uiState.value is TextConverterViewModel.UiState.Success)
    }
    @Test fun oversizedSingleLineIsRejectedWithoutTruncatingUserContent() {
        every { resolver.openInputStream(any()) } returns "x".repeat(2_000_001).byteInputStream()
        viewModel.setInputText("retained input")
        viewModel.loadFile(context, Uri.parse("content://fixture/large.txt"))
        awaitCompletion()
        assertEquals("retained input", viewModel.inputText.value)
        assertTrue(viewModel.uiState.value is TextConverterViewModel.UiState.Error)
    }
}
