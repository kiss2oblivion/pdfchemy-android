package com.pdfchemy.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.Screen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeSearchReviewTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun emptyOrBlankQueryReturnsNoTools() {
        val emptyResult = ToolRegistry.search("") { context.getString(it) }
        assertTrue("Empty query must return empty list", emptyResult.isEmpty())

        val blankResult = ToolRegistry.search("   \t  ") { context.getString(it) }
        assertTrue("Blank query must return empty list", blankResult.isEmpty())
    }

    @Test
    fun csvQueryResolvesToTableExtractor() {
        val results = ToolRegistry.search("csv") { context.getString(it) }
        assertTrue("Search for 'csv' must return results", results.isNotEmpty())
        assertTrue("Search for 'csv' must contain TableExtractor", results.any { it.screen is Screen.TableExtractor })
    }

    @Test
    fun extractTablesQueryResolvesToTableExtractor() {
        val results = ToolRegistry.search("extract tables") { context.getString(it) }
        assertTrue("Search for 'extract tables' must return results", results.isNotEmpty())
        assertTrue("Exact alias match must resolve to TableExtractor", results.any { it.screen is Screen.TableExtractor })
    }

    @Test
    fun readPdfQueryResolvesToPdfReader() {
        val results = ToolRegistry.search("read pdf") { context.getString(it) }
        assertTrue("Search for 'read pdf' must return results", results.isNotEmpty())
        assertTrue("Search for 'read pdf' must contain PdfReader", results.any { it.screen is Screen.PdfReader })
    }

    @Test
    fun directionalConversionsAreDistinguishedAccurately() {
        val toText = ToolRegistry.search("pdf to text") { context.getString(it) }
        assertTrue("pdf to text must resolve to ExtractText", toText.any { it.screen is Screen.ExtractText })

        val fromText = ToolRegistry.search("text to pdf") { context.getString(it) }
        assertTrue("text to pdf must resolve to TextToPdf", fromText.any { it.screen is Screen.TextToPdf })
    }

    @Test
    fun nonexistentQueryReturnsEmptyList() {
        val results = ToolRegistry.search("xyz_nonexistent_query_999") { context.getString(it) }
        assertTrue("Unmatched query must return empty list", results.isEmpty())
    }
}
