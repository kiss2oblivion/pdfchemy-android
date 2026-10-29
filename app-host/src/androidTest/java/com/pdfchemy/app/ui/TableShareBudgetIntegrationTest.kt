package com.pdfchemy.app.ui

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.DocumentActions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TableShareBudgetIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualTableShareCannotCreateARecipientCopyBeyondTheSharedBudget() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val tag = "table_budget_${System.nanoTime()}"
        val created = mutableListOf<File>()
        val before = DocumentActions.inspectRetainedShares(app)
        val retained = File(app.filesDir, "shared_copies").apply { check(isDirectory || mkdirs()) }
        val cacheExports = File(app.cacheDir, "exports")
        val initialLegacy = cacheExports.listFiles().orEmpty().map { it.name }.toSet()
        val launched = AtomicInteger()
        val context = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) { launched.incrementAndGet() }
            override fun startActivity(intent: Intent, options: android.os.Bundle?) { launched.incrementAndGet() }
        }
        val store = ViewModelStore()
        val model = MainViewModel(app).also { store.put(tag, it) }
        val visible = mutableStateOf(true)
        val source = File(app.cacheDir, "$tag.pdf")
        try {
            repeat((DocumentActions.MAX_RETAINED_SHARE_COPIES - before.totalCount).coerceAtLeast(0)) { index ->
                File(retained, "shared_${tag}_$index.csv").also { it.writeText("retained,$index\n"); created += it }
            }
            assertTrue(DocumentActions.inspectRetainedShares(app).totalCount >= DocumentActions.MAX_RETAINED_SHARE_COPIES)
            PDFBoxResourceLoader.init(app)
            PDDocument().use { doc ->
                val page = PDPage().also(doc::addPage)
                PDPageContentStream(doc, page).use { stream ->
                    for ((row, values) in listOf(listOf("Name", "Value"), listOf("Alpha", "42")).withIndex()) {
                        for ((column, value) in values.withIndex()) {
                            stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                            stream.newLineAtOffset(50f + 150f * column, 700f - 30f * row)
                            stream.showText(value); stream.endText()
                        }
                    }
                }
                doc.save(source)
            }
            val original = source.readBytes()
            compose.setContent { MaterialTheme {
                if (visible.value) CompositionLocalProvider(LocalContext provides context) {
                    TableExtractorScreen(model, Uri.fromFile(source)) { }
                }
            } }
            val description = app.getString(R.string.table_extractor_share_desc)
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(description).performClick()
            val quotaError = app.getString(R.string.integrity_share_storage_full)
            compose.waitUntil(15_000) { compose.onAllNodesWithText(quotaError).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(quotaError).assertIsDisplayed()
            compose.onNodeWithContentDescription(description).assertIsEnabled()
            compose.waitForIdle()
            assertEquals("No recipient may receive a copy when the retention budget is full", 0, launched.get())
            assertEquals("CSV shares must use the same retention quota as PDF shares",
                before.totalCount + created.size, DocumentActions.inspectRetainedShares(app).totalCount)
            assertArrayEquals(original, source.readBytes())
            created.forEach { assertTrue("Previously retained recipient content must remain", it.isFile) }
        } finally {
            try {
                compose.runOnUiThread { visible.value = false }; compose.waitForIdle()
            } finally {
                store.clear(); source.delete()
                created.forEach { check(it.delete()) }
                // The old implementation creates a UUID legacy export; remove only entries
                // introduced by this single, isolated UI test, never pre-existing recipients.
                cacheExports.listFiles().orEmpty().filter {
                    it.name.startsWith("shared_table_") && it.name !in initialLegacy
                }.forEach { check(it.delete()) }
            }
        }
    }
}
