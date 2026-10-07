package com.pdfchemy.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.Screen
import com.pdfchemy.app.logic.OfficeFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun search(query: String) = ToolRegistry.search(query) { context.getString(it) }.map { it.screen }

    @Test fun everyFeatureHasExactlyOneDestinationAndAllOfficeFormatsAreCovered() {
        val navigation = setOf(Screen.Home::class, Screen.Settings::class, Screen.Premium::class,
            Screen.CompressCategory::class, Screen.CreateCategory::class,
            Screen.OrganizeCategory::class, Screen.CheckCategory::class)
        val features = Screen::class.sealedSubclasses.toSet() - navigation
        assertEquals(features, ToolRegistry.allTools.map { it.screen::class }.toSet())
        assertEquals(ToolRegistry.allTools.size, ToolRegistry.allTools.map { it.screen }.distinct().size)
        assertEquals(OfficeFormat.values().toSet(), ToolRegistry.allTools.mapNotNull {
            (it.screen as? Screen.OfficeExport)?.initialFormat
        }.toSet())
    }

    @Test fun conceptualQueriesReachDistinctCorrectTools() {
        assertEquals(listOf(Screen.OcrPdf), search("text recognition"))
        assertEquals(listOf(Screen.ExtractText), search("pdf to text"))
        assertEquals(listOf(Screen.PdfToImages), search("pdf to jpg"))
        assertTrue(search("extract embedded images").contains(Screen.ExtractImages))
        assertEquals(listOf(Screen.OfficeExport(OfficeFormat.WORD)), search("docx"))
        assertTrue(search("audio export").contains(Screen.ReflowReader()))
        assertTrue(search("read pdf").contains(Screen.PdfReader()))
        assertTrue(search("remove password").contains(Screen.UnlockPdf()))
        assertTrue(search("black and white").contains(Screen.GrayscaleOptimizer))
    }

    @Test fun matchingIgnoresWhitespaceOrderAndLocaleCaseRules() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(search("shrink"), search("  SHRINK  "))
            assertEquals(search("pdf to word"), search("pdf   to word"))
            assertTrue(search("word pdf").contains(Screen.OfficeExport(OfficeFormat.WORD)))
        } finally { Locale.setDefault(original) }
    }

    @Test fun emptyUnknownAndRepeatedQueriesArePredictable() {
        assertTrue(search("   ").isEmpty())
        assertTrue(search("not-a-tool-9381").isEmpty())
        assertEquals(search("pdf"), search("pdf"))
        ToolRegistry.allTools.forEach {
            assertTrue(context.getString(it.nameRes).isNotBlank())
            assertTrue(context.getString(it.descriptionRes).isNotBlank())
        }
    }
}
