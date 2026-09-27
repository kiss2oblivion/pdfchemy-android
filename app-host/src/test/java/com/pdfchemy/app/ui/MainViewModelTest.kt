package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: MainViewModel
    private lateinit var application: Application
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        viewModel = MainViewModel(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(MainViewModel.UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun setHapticEnabled_updatesState() {
        viewModel.setHapticEnabled(false)
        assertEquals(false, viewModel.isHapticEnabled.value)
        
        viewModel.setHapticEnabled(true)
        assertEquals(true, viewModel.isHapticEnabled.value)
    }

    @Test
    fun resetState_resetsToIdle() {
        // Assume some state changes happened
        viewModel.resetState()
        assertEquals(MainViewModel.UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun compressPdf_updatesUiStateAndReturnsSuccess() = runTest {
        // Create dummy PDF
        val sourceFile = File(context.cacheDir, "test_compress.pdf")
        val document = PDDocument()
        document.addPage(PDPage())
        FileOutputStream(sourceFile).use { document.save(it) }
        document.close()

        val destFile = File(context.cacheDir, "test_compress_out.pdf")
        val destUri = Uri.fromFile(destFile)

        viewModel.compressPdf(context, Uri.fromFile(sourceFile), destUri)

        // Advance dispatcher to execute coroutines
        advanceUntilIdle()
        // wait for IO thread if needed by sleeping real thread (hacky but works for Robolectric + IO)
        Thread.sleep(1000)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        
        assertTrue("Expected Success but got $state", state is MainViewModel.UiState.Success)

        // Cleanup
        sourceFile.delete()
        destFile.delete()
    }

    @Test
    fun extractTextFromPdf_updatesUiStateAndReturnsSuccess() = runTest {
        val sourceFile = File(context.cacheDir, "test_extract.pdf")
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        
        val font = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
        com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use { contentStream ->
            contentStream.beginText()
            contentStream.setFont(font, 12f)
            contentStream.newLineAtOffset(100f, 700f)
            contentStream.showText("This is a long text to prevent OCR fallback. It must be at least fifty characters long to pass the check.")
            contentStream.endText()
        }
        
        FileOutputStream(sourceFile).use { document.save(it) }
        document.close()

        val destFile = File(context.cacheDir, "test_extract_out.txt")
        val destUri = Uri.fromFile(destFile)

        viewModel.extractTextFromPdf(context, Uri.fromFile(sourceFile), destUri)

        advanceUntilIdle()
        Thread.sleep(1000)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        if (state !is MainViewModel.UiState.Success) {
            throw RuntimeException("Expected Success but got $state")
        }

        sourceFile.delete()
        destFile.delete()
    }
}
