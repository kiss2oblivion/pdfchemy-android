package com.pdfchemy.app.ui

import android.app.Application
import android.os.ParcelFileDescriptor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.SettingsScreen
import com.pdfchemy.app.logic.DocumentActions
import com.pdfchemy.app.logic.RetainedShareProtection
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RetainedShareSettingsIntegrityTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val model = MainViewModel(app).also { store.put("share-settings", it) }
    private val ownedFiles = mutableListOf<File>()
    private var reader: ParcelFileDescriptor? = null

    @Before fun dedicatedStorage() {
        assertTrue("Preserve pre-existing copies; this fixture requires no unrelated inactive shares",
            DocumentActions.inspectRetainedShares(app).entries.none { it.protections.isEmpty() })
    }

    @After fun cleanup() {
        reader?.close()
        reader = null
        compose.waitUntil(30_000) {
            DocumentActions.inspectRetainedShares(app).entries
                .filter { entry -> ownedFiles.any { it.name == entry.displayName } }
                .none { RetainedShareProtection.ACTIVE_READER in it.protections }
        }
        store.clear()
        runBlocking { model.lastCleanupJob?.join() }
        ownedFiles.forEach { it.delete() }
    }

    private fun copy(): Pair<File, ByteArray> {
        val bytes = "%PDF-1.4\nshared settings fixture ${UUID.randomUUID()}\n%%EOF".toByteArray()
        val folder = File(app.filesDir, "shared_copies").apply { mkdirs() }
        return File(folder, "shared_settings_${UUID.randomUUID()}.pdf")
            .also { it.writeBytes(bytes); ownedFiles.add(it) } to bytes
    }

    private fun showSettings() {
        compose.setContent { MaterialTheme { SettingsScreen(model, onBack = {}) } }
    }

    private fun openManagement() {
        compose.onNodeWithText(app.getString(R.string.integrity_share_manage))
            .performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(app.getString(R.string.integrity_share_delete_inactive)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickDelete() {
        compose.onNodeWithText(app.getString(R.string.integrity_share_delete_inactive)).performClick()
        compose.onNode(hasText(app.getString(R.string.integrity_share_delete_confirm)) and hasClickAction())
            .performClick()
    }

    @Test fun cancellingExplicitCleanupKeepsCopiesAndOriginal() {
        val (first, firstBytes) = copy()
        val (second, secondBytes) = copy()
        val original = File.createTempFile("share-settings-original", ".pdf", app.cacheDir)
            .also { it.writeBytes(firstBytes); ownedFiles.add(it) }
        showSettings()
        openManagement()

        compose.onNodeWithText(app.getString(R.string.integrity_share_delete_inactive)).performClick()
        assertTrue(first.exists() && second.exists())
        compose.onNodeWithText(app.getString(R.string.cancel)).performClick()

        assertArrayEquals(firstBytes, first.readBytes())
        assertArrayEquals(secondBytes, second.readBytes())
        assertArrayEquals(firstBytes, original.readBytes())
    }

    @Test fun cleanupKeepsActiveProviderReaderAndDeletesOnlyInactiveCopy() {
        val (active, bytes) = copy()
        val (inactive, _) = copy()
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", active)
        reader = requireNotNull(app.contentResolver.openFileDescriptor(uri, "r"))
        assertTrue(DocumentActions.inspectRetainedShares(app).entries.single { it.displayName == active.name }
            .protections.contains(RetainedShareProtection.ACTIVE_READER))
        showSettings()
        openManagement()

        clickDelete()
        compose.waitUntil(30_000) { !inactive.exists() }

        assertArrayEquals(bytes, active.readBytes())
        val opened = requireNotNull(reader)
        reader = null
        assertArrayEquals(bytes, ParcelFileDescriptor.AutoCloseInputStream(opened).use { it.readBytes() })
        assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun clearingRecentHistoryThroughSettingsPreservesCompletedShare() {
        val (copy, bytes) = copy()
        showSettings()

        compose.onNodeWithText(app.getString(R.string.settings_clear_history)).performScrollTo().performClick()
        compose.onNode(hasText(app.getString(R.string.clear)) and hasClickAction()).performClick()
        runBlocking {
            withTimeout(30_000) { model.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
        }

        assertTrue(copy.exists())
        assertArrayEquals(bytes, copy.readBytes())
        openManagement()
    }
}
