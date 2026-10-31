package com.pdfchemy.app.ui

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.MainApp
import com.pdfchemy.app.R
import com.pdfchemy.app.ui.textconverter.TextConverterViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real MainApp result layout; synthetic completion here is not SDK ad-delivery evidence. */
class OperationResultReachabilityTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val model = MainViewModel(app).also { store.put("result-layout", it) }
    private val converter = TextConverterViewModel().also { store.put("result-text", it) }
    private val files = mutableListOf<Pair<File, ByteArray>>()

    @After fun cleanup() {
        store.clear()
        files.forEach { (file, bytes) ->
            assertArrayEquals("Viewing a result must preserve its published output", bytes, file.readBytes())
            assertTrue(file.delete())
        }
    }

    private fun output(): Uri {
        val bytes = "%PDF-1.4\nResult layout fixture\n%%EOF".toByteArray()
        val exports = File(app.cacheDir, "exports").apply { mkdirs() }
        val file = File.createTempFile("result-layout-", ".pdf", exports)
        file.writeBytes(bytes)
        files.add(file to bytes)
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    private fun showApp(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme { MainApp(viewModel = model, textConverterViewModel = converter) }
            }
        }
        compose.waitForIdle()
    }

    private fun resultAction(label: String) = compose.onNode(
        hasText(label) and hasClickAction() and hasAnyAncestor(isDialog())
    )

    @Test fun compressionResultActionsRemainReachableAtDoubleFontScale() {
        val uri = output()
        showApp(2f)
        compose.runOnIdle {
            model.setUiStateForTesting(MainViewModel.UiState.Success(
                "Compression Result",
                "The output is slightly larger than the original. This PDF may already be highly optimized.\n\n" +
                    "Verdict: No compression benefit\n\nOriginal Size: 1.6 KB\nCompressed Size: 1.6 KB\n\n" +
                    "Settings Used:\n- Quality Preset: 75%\n- Grayscale: Disabled\n- Lossless ZIP: Enabled\n- Metadata Removed: Yes",
                listOf(uri)
            ))
        }
        compose.onNode(isDialog()).assertExists()
        listOf("Browse files", app.getString(R.string.btn_open_file), app.getString(R.string.share),
            "Another Operation", "Home").forEach { label ->
            resultAction(label).performScrollTo().assertIsDisplayed()
        }
        resultAction("Home").performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(MainViewModel.UiState.Idle, model.uiState.value)
            assertNull(model.pendingAdOpportunity.value)
            assertNull("Repeated Home checks cannot consume this output again", model.consumeAdOpportunity())
        }
    }

    @Test fun anotherOperationRemainsReachableAfterLongMultiOutputResult() {
        val outputs = List(5) { output() }
        showApp(1f)
        compose.runOnIdle {
            model.setUiStateForTesting(MainViewModel.UiState.Success(
                "Extraction Result", List(40) { "Completed page ${it + 1}: saved output." }.joinToString("\n"), outputs
            ))
        }
        resultAction("Another Operation").performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(MainViewModel.UiState.Idle, model.uiState.value)
            assertNull(model.pendingAdOpportunity.value)
        }
    }
}
