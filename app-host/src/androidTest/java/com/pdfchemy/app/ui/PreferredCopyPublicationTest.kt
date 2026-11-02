package com.pdfchemy.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.logic.OutputDestination
import com.pdfchemy.app.logic.OutputPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PreferredCopyPublicationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun noSavedFolderChoosesTreeAndReturnsProvenFreshSeparateCopy() = exercise(false)
    @Test fun cancellingFolderPickerCreatesNoDocument() = exercise(true)
    @Test fun savedFolderIsReusedWithoutLaunchingPicker() = exercise(saved = true)
    @Test fun unavailableSavedFolderFallsBackToNewFolderCopy() = exercise(saved = true, unavailable = true)
    @Test fun repeatedSaveCallbacksReceiveDistinctNumberedCopies() = exercise(repeated = true)
    @Test fun providerReturningExistingDocumentIsRejectedWithoutOpeningIt() = exercise(returnExisting = true)
    @Test fun disposalDuringProviderCreationCleansOnlyTheNewCopy() = exercise(disposeDuringCreation = true)

    private fun exercise(cancel: Boolean = false, saved: Boolean = false, unavailable: Boolean = false,
        repeated: Boolean = false, returnExisting: Boolean = false, disposeDuringCreation: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        val previous = prefs.getString(OutputPolicy.DIRECTORY_KEY, null)
        prefs.edit().remove(OutputPolicy.DIRECTORY_KEY).commit()
        val base = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        context.contentResolver.call(base, "configure", null, Bundle().apply {
            putBoolean("returnExisting", returnExisting); putBoolean("blockCreation", disposeDuringCreation)
        })
        val tree = DocumentsContract.buildTreeDocumentUri(base.authority!!, "root")
        val old = DocumentsContract.buildDocumentUriUsingTree(tree, "existing")
        if (saved) prefs.edit().putString(OutputPolicy.DIRECTORY_KEY,
            (if (unavailable) DocumentsContract.buildTreeDocumentUri(base.authority!!, "missing") else tree).toString()).commit()
        val bytes = context.contentResolver.openInputStream(old)!!.use { it.readBytes() }
        val result = AtomicReference<OutputDestination?>()
        val returned = AtomicInteger()
        val launches = AtomicInteger()
        val copies = mutableListOf<OutputDestination>()
        val visible = mutableStateOf(true)
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.OpenDocumentTree)
                launches.incrementAndGet()
                dispatchResult(code, if (cancel) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    Intent().setData(if (cancel) null else tree))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme { if (visible.value) {
                        val launcher = rememberPreferredDocumentDestinationCreator("application/pdf") {
                            result.set(it); if (it != null) copies.add(it); returned.incrementAndGet()
                        }
                        Button(onClick = { launcher.launch("existing.pdf") }) { Text("Save fixture") }
                    } }
                }
            }
            val requests = if (repeated) 2 else 1
            repeat(requests) { index ->
                compose.onNodeWithText("Save fixture").performClick()
                if (saved) compose.onNodeWithText("Save copy").performClick()
                if (disposeDuringCreation) {
                    compose.waitUntil(10_000) {
                        context.contentResolver.call(base, "creationState", null, null)!!.getBoolean("entered")
                    }
                    compose.runOnIdle { visible.value = false }
                    compose.waitForIdle() // Removal/cancellation must finish before unblocking the producer.
                    context.contentResolver.call(base, "releaseCreation", null, null)
                    val fresh = DocumentsContract.buildDocumentUriUsingTree(tree, "doc_1")
                    compose.waitUntil(10_000) {
                        context.contentResolver.call(base, "status", null, null)!!.getInt("deletedCount") == 1 &&
                            !OutputPolicy.isFreshCopy(fresh)
                    }
                    assertEquals(0, returned.get())
                } else {
                    compose.waitUntil(30_000) { returned.get() == index + 1 }
                    if (cancel || returnExisting) assertNull(result.get()) else {
                        val output = requireNotNull(result.get())
                        assertTrue(output.isFreshCopy)
                        assertTrue(OutputPolicy.isFreshCopy(output.uri))
                        assertNotEquals(old, output.uri)
                        assertEquals("existing (${index + 2}).pdf", DocumentFile.fromSingleUri(context, output.uri)!!.name)
                    }
                }
            }
            val state = context.contentResolver.call(base, "status", null, null)!!
            assertEquals(if (cancel || returnExisting) 0 else requests, state.getInt("createdCount"))
            assertEquals(if (saved && !unavailable) 0 else requests, launches.get())
            assertEquals(copies.size, copies.map { it.uri }.distinct().size)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(old)!!.use { it.readBytes() })
        } finally {
            context.contentResolver.call(base, "releaseCreation", null, null)
            copies.forEach { OutputPolicy.deleteIfFresh(context, it.uri) }
            prefs.edit().apply {
                if (previous == null) remove(OutputPolicy.DIRECTORY_KEY) else putString(OutputPolicy.DIRECTORY_KEY, previous)
            }.commit()
        }
    }
}
