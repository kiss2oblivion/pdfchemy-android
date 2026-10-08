package com.pdfchemy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.CategoryCard
import com.pdfchemy.app.ToolCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemainingUxRuntimeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun preferredSaveWrapperComposesWithoutRecursion() {
        compose.setContent { MaterialTheme {
            rememberPreferredDocumentCreator("application/pdf") { }
            Text("Save wrapper ready")
        } }
        compose.onNodeWithText("Save wrapper ready").assertIsDisplayed()
    }

    @Test fun enlargedSearchAcceptsQueryAndSelectsReader() {
        var destination: com.pdfchemy.app.Screen? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                MaterialTheme { ToolSearchBar(onToolSelected = { destination = it }) }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("read pdf")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val title = context.getString(ToolRegistry.allTools.first { it.screen is com.pdfchemy.app.Screen.PdfReader }.nameRes)
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(destination is com.pdfchemy.app.Screen.PdfReader) }
    }

    @Test fun categoryAndToolLabelsFitAt320dpAndDoubleFontScale() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        CategoryCard(title="Compress",subtitle="Reduce PDF file size",icon=Icons.Default.Description,
                            modifier=Modifier.width(144.dp).height(300.dp),onClick={})
                        ToolCard(title="Organize pages",subtitle="Move, rotate and delete pages",icon=Icons.Default.Description,onClick={})
                    }
                }
            }
        }
        for (label in listOf("Compress","Reduce PDF file size","Organize pages","Move, rotate and delete pages")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree=true).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing layout for $label", layouts.isNotEmpty())
            assertFalse("Clipped label: $label; ${layouts.map { "${it.size} lines=${it.lineCount} bounds=${(0 until it.lineCount).map { line -> it.getLineLeft(line) to it.getLineRight(line) }}" }}",
                layouts.any { layout -> layout.didOverflowHeight || (0 until layout.lineCount).any { line ->
                    layout.isLineEllipsized(line) || layout.getLineLeft(line) < -1f || layout.getLineRight(line) > layout.size.width + 1f
                } })
        }
    }
}
