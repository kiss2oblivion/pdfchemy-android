package com.pdfchemy.app.ui

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.utils.FileUtils
import com.pdfchemy.app.utils.ProviderMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ProviderMetadataDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun uri(path: String) = Uri.parse("content://com.pdfchemy.app.test.metadata/$path")
    @Before fun warmProvider() { context.contentResolver.call(uri("control"), "reset", null, null); ProviderMetadata.clear() }
    private suspend fun awaitActive(count: Int) = withTimeout(3000) {
        while ((context.contentResolver.call(uri("control"), "active", null, null)?.getInt("active") ?: 0) < count) delay(10)
    }
    @After fun clear() { ProviderMetadata.clear(); Thread.sleep(2700) }

    @Test fun legacyMainGettersNeverWaitForProvider() {
        val u = uri("slow/main")
        var elapsed = 0L
        compose.runOnUiThread {
            val start = SystemClock.elapsedRealtime()
            assertEquals("main", FileUtils.getFileName(context, u))
            assertEquals(0L, FileUtils.getFileSize(context, u))
            assertEquals("application/octet-stream", FileUtils.getMimeType(context, u))
            elapsed = SystemClock.elapsedRealtime() - start
        }
        assertTrue("Main made provider IPC", elapsed < 500)
    }

    @Test fun normalOpaqueDocumentIdKeepsProviderNameSizeAndMime() = runBlocking<Unit> {
        val u = uri("normal/123")
        val value = ProviderMetadata.load(context, u)
        assertEquals("provider-123.pdf", value.name); assertEquals(123L, value.size)
        compose.runOnUiThread {
            assertEquals("provider-123.pdf", FileUtils.getFileName(context, u))
            assertEquals("application/pdf", FileUtils.getMimeType(context, u))
        }
    }

    @Test fun blockedQueryTimesOutAndLateValueIsDiscarded() = timeout("slow/query")
    @Test fun blockedMimeTimesOutAndLateValueIsDiscarded() = timeout("slow-type/type")
    private fun timeout(path: String) = runBlocking<Unit> {
        val u = uri(path); val start = SystemClock.elapsedRealtime()
        assertEquals(ProviderMetadata.Value(), ProviderMetadata.load(context, u))
        assertTrue(SystemClock.elapsedRealtime() - start < 1800)
        delay(2700)
        assertNull("Timed-out request must not populate cache", ProviderMetadata.cached(u))
    }

    @Test fun cancellationReturnsPromptlyWithoutPublishingLateMetadata() = runBlocking<Unit> {
        val u = uri("slow/cancel")
        val job = launch(Dispatchers.Default) { ProviderMetadata.load(context,u); fail("Cancelled load returned") }
        awaitActive(1)
        val start = SystemClock.elapsedRealtime(); job.cancelAndJoin()
        assertTrue(SystemClock.elapsedRealtime() - start < 600)
        delay(2700); assertNull(ProviderMetadata.cached(u))
    }

    @Test fun saturatedProviderPoolRejectsMoreWorkPromptly() = runBlocking<Unit> {
        val first = async(Dispatchers.Default) { ProviderMetadata.load(context, uri("slow/one")) }
        val second = async(Dispatchers.Default) { ProviderMetadata.load(context, uri("slow/two")) }
        awaitActive(2)
        val start = SystemClock.elapsedRealtime()
        repeat(30) { assertEquals(ProviderMetadata.Value(), ProviderMetadata.load(context, uri("normal/rejected-$it"))) }
        assertTrue("Provider work must not queue or spawn unlimited workers", SystemClock.elapsedRealtime() - start < 600)
        first.await(); second.await()
    }

    @Test fun slowOldLabelCannotReplaceNewSelectionAndUiResponds() {
        val selected = mutableStateOf(uri("slow/A"))
        compose.setContent {
            val metadata = rememberDocumentMetadata(selected.value)
            var clicks by remember { mutableStateOf(0) }
            androidx.compose.foundation.layout.Column {
                Button(onClick = { clicks++ }) { Text("heartbeat-$clicks") }
                Text(metadata.name ?: "loading")
            }
        }
        compose.onNodeWithText("heartbeat-0").performClick()
        compose.onNodeWithText("heartbeat-1").assertExists()
        compose.runOnIdle { selected.value = uri("normal/B") }
        compose.waitUntil(5000) { compose.onAllNodesWithText("provider-B.pdf").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(2800)
        compose.onNodeWithText("provider-B.pdf").assertExists()
        compose.onNodeWithText("provider-A.pdf").assertDoesNotExist()
    }
    @Test fun historyKeepsResolvedIdentityAndClearRejectsPendingMetadata() = runBlocking<Unit> {
        val app = context as android.app.Application
        val settings = app.getSharedPreferences("shrinkpdf_settings", 0)
        val saved = app.getSharedPreferences("pdfchemy_history", 0)
        val oldEnabled = settings.getBoolean("history_enabled", false)
        val oldHistory = saved.getString("recent_files", null)
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var model: MainViewModel
        try {
            compose.runOnUiThread {
                model = MainViewModel(app); store.put("metadata-history", model)
                model.setHistoryEnabled(true)
                model.recordReaderHistory(uri("normal/history"), "fallback.pdf")
            }
            val items = withTimeout(5000) { model.historyList.first { values -> values.any { it.name == "provider-history.pdf" } } }
            assertEquals("application/pdf", items.single { it.name == "provider-history.pdf" }.mimeType)
            compose.runOnUiThread { model.recordReaderHistory(uri("slow/obsolete-history"), "old.pdf") }
            awaitActive(1)
            compose.runOnUiThread { model.clearHistory() }
            delay(2800)
            assertTrue(model.historyList.value.isEmpty())
            assertTrue(com.pdfchemy.app.logic.HistoryRepository(app).getHistory().isEmpty())
        } finally {
            compose.runOnUiThread { store.clear() }
            settings.edit().putBoolean("history_enabled", oldEnabled).commit()
            saved.edit().apply { if (oldHistory == null) remove("recent_files") else putString("recent_files", oldHistory) }.commit()
        }
    }

    @Test fun newOperationRefreshesRenamedDocumentAtSameUri() = runBlocking<Unit> {
        val source = uri("normal/rename")
        assertEquals("provider-rename.pdf", ProviderMetadata.load(context, source).name)
        context.contentResolver.call(uri("control"), "rename", "Quarterly Report.pdf", null)
        assertEquals("Quarterly Report.pdf", ProviderMetadata.load(context, source).name)
        compose.runOnUiThread { assertEquals("Quarterly Report.pdf", FileUtils.getFileName(context, source)) }
    }

}
