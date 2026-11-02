package com.pdfchemy.app.logic

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryShareRetentionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val ownedFiles = mutableListOf<File>()
    private lateinit var model: MainViewModel

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app.getSharedPreferences("shrinkpdf_settings", 0).edit().putBoolean("history_enabled", true).commit()
        model = MainViewModel(app).also { store.put("history-share-test", it) }
    }

    @After fun cleanup() = runBlocking {
        store.clear()
        model.lastCleanupJob?.join()
        ownedFiles.forEach { it.delete() }
        Dispatchers.resetMain()
    }

    private fun completedCopy(): Pair<File, ByteArray> {
        val bytes = "%PDF-1.4\nrecipient copy ${UUID.randomUUID()}\n%%EOF".toByteArray()
        val folder = File(app.filesDir, "shared_copies").apply { mkdirs() }
        val copy = File(folder, "shared_history_${UUID.randomUUID()}.pdf")
            .also { it.writeBytes(bytes); ownedFiles.add(it) }
        assertTrue(DocumentActions.inspectRetainedShares(app).entries.any {
            it.displayName == copy.name && it.kind == RetainedShareKind.RETAINED_COPY && it.protections.isEmpty()
        })
        return copy to bytes
    }

    private suspend fun finishHistoryWork() {
        withTimeout(5_000) {
            model.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
        }
    }

    @Test fun clearingRecentHistoryPreservesCompletedRecipientCopy() = runBlocking {
        val (copy, bytes) = completedCopy()
        model.recordReaderHistory(Uri.fromFile(copy), "Reader document")
        assertTrue(model.historyList.value.isNotEmpty())

        model.clearHistory()
        finishHistoryWork()

        assertTrue(model.historyList.value.isEmpty())
        assertTrue("Clearing recent entries must not delete a recipient's retained copy", copy.exists())
        assertArrayEquals(bytes, copy.readBytes())
    }

    @Test fun disablingHistoryPreservesCompletedRecipientCopy() = runBlocking {
        val (copy, bytes) = completedCopy()

        model.setHistoryEnabled(false)
        finishHistoryWork()

        assertFalse(model.isHistoryEnabled.value)
        assertTrue("Disabling history must not invalidate an existing share", copy.exists())
        assertArrayEquals(bytes, copy.readBytes())
    }

    @Test fun explicitSelectedCleanupDeletesOnlyTheRequestedCopy() {
        val (selected, _) = completedCopy()
        val (retained, bytes) = completedCopy()
        val id = DocumentActions.inspectRetainedShares(app).entries.single { it.displayName == selected.name }.id

        val result = DocumentActions.cleanupRetainedShares(app, setOf(id))

        assertEquals(setOf(id), result.deleted.keys)
        assertTrue(result.complete)
        assertFalse(selected.exists())
        assertTrue(retained.exists())
        assertArrayEquals(bytes, retained.readBytes())
    }
}
