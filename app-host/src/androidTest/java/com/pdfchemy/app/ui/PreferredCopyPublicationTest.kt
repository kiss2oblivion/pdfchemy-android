package com.pdfchemy.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class PreferredCopyPublicationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun noSavedFolderChoosesTreeAndReturnsProvenFreshSeparateCopy() = exercise(false)
    @Test fun cancellingFolderPickerCreatesNoDocument() = exercise(true)

    private fun exercise(cancel: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        val previous = prefs.getString(OutputPolicy.DIRECTORY_KEY, null)
        prefs.edit().remove(OutputPolicy.DIRECTORY_KEY).commit()
        val base = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        context.contentResolver.call(base, "configure", null, null)
        val tree = DocumentsContract.buildTreeDocumentUri(base.authority!!, "root")
        val old = DocumentsContract.buildDocumentUriUsingTree(tree, "existing")
        val bytes = context.contentResolver.openInputStream(old)!!.use { it.readBytes() }
        val result = AtomicReference<OutputDestination?>()
        val returned = AtomicBoolean()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.OpenDocumentTree)
                dispatchResult(code, if (cancel) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    Intent().setData(if (cancel) null else tree))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme {
                        val launcher = rememberPreferredDocumentDestinationCreator("application/pdf") {
                            result.set(it); returned.set(true)
                        }
                        Button(onClick = { launcher.launch("existing.pdf") }) { Text("Save fixture") }
                    }
                }
            }
            compose.onNodeWithText("Save fixture").performClick()
            compose.waitUntil(30_000) { returned.get() }
            val state = context.contentResolver.call(base, "status", null, null)!!
            assertEquals(if (cancel) 0 else 1, state.getInt("createdCount"))
            if (cancel) assertNull(result.get()) else {
                val output = requireNotNull(result.get())
                assertTrue(output.isFreshCopy)
                assertTrue(OutputPolicy.isFreshCopy(output.uri))
                assertNotEquals(old, output.uri)
                assertEquals("existing (2).pdf", DocumentFile.fromSingleUri(context, output.uri)!!.name)
            }
            assertArrayEquals(bytes, context.contentResolver.openInputStream(old)!!.use { it.readBytes() })
        } finally {
            result.get()?.let { OutputPolicy.deleteIfFresh(context, it.uri) }
            prefs.edit().apply {
                if (previous == null) remove(OutputPolicy.DIRECTORY_KEY) else putString(OutputPolicy.DIRECTORY_KEY, previous)
            }.commit()
        }
    }
}
