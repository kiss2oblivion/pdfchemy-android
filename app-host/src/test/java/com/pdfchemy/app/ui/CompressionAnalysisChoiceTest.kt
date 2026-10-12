package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.PdfAnalysis
import com.pdfchemy.app.logic.PdfCompressor
import com.pdfchemy.app.logic.PdfScenario
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompressionAnalysisChoiceTest {
    @Test fun dismissKeepsChoicesAndNewBatchLoadsSavedDefaults() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val prefs = application.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        com.pdfchemy.app.logic.CompressionDefaultsStore(prefs).save(
            com.pdfchemy.app.logic.CompressionDefaults(0.75f, true, true, false)
        )
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(PdfCompressor)
        try {
            coEvery { PdfCompressor.analyzePdf(any(), any()) } returns Result.failure(IllegalStateException("unreadable"))
            val model = MainViewModel(application)
            model.setQuality(0.25f)
            model.setUseLossless(false)
            model.resetState()
            assertEquals(0.25f, model.compressionQuality.value)
            assertFalse(model.useLossless.value)
            model.onFilesSelected(application, listOf(Uri.parse("content://missing-provider/document/batch.pdf")))
            withTimeout(10_000) {
                while (model.selectedFiles.value.isEmpty() || model.selectedFiles.value.any { it.isAnalyzing }) delay(10)
            }
            assertEquals(0.75f, model.compressionQuality.value)
            assertTrue(model.useGrayscale.value)
            assertTrue(model.useLossless.value)
            assertFalse(model.stripMetadata.value)
        } finally {
            unmockkObject(PdfCompressor)
            Dispatchers.resetMain()
        }
    }

    @Test fun viewModelHonorsChoicesMadeWhileAnalysisIsInFlight() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        application.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE).edit().clear().commit()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(PdfCompressor)
        try {
            val started = CompletableDeferred<Unit>()
            val complete = CompletableDeferred<Unit>()
            val analysis = mockk<PdfAnalysis>(relaxed = true)
            every { analysis.recommendedQuality } returns 0.25f
            every { analysis.scenario } returns PdfScenario.MIXED
            coEvery { PdfCompressor.analyzePdf(any(), any()) } coAnswers {
                started.complete(Unit)
                complete.await()
                Result.success(analysis)
            }
            val model = MainViewModel(application)
            model.onFileSelected(application, Uri.parse("content://missing-provider/document/test.pdf"))
            withTimeout(10_000) { started.await() }
            model.setQuality(0.75f)
            model.setUseLossless(true)
            complete.complete(Unit)
            withTimeout(10_000) {
                while (model.pdfAnalysis.value != analysis || !model.stripMetadata.value) delay(10)
            }
            assertEquals(0.75f, model.compressionQuality.value)
            assertTrue(model.useLossless.value)
            assertTrue(model.stripMetadata.value) // Untouched choice still receives its recommendation.
        } finally {
            unmockkObject(PdfCompressor)
            Dispatchers.resetMain()
        }
    }
}
