package com.pdfchemy.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.SanitizerAuditReport
import com.pdfchemy.app.logic.SanitizerResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SanitizerRequestOwnershipTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun ready() = app.getString(R.string.sanitizer_action_sanitize)
    private fun success() = SanitizerResult(true, 0, 0, 0, true, 0)

    private inner class Harness(
        val audit: suspend (Uri) -> SanitizerAuditReport,
        val clean: suspend (SanitizerRequest, Uri) -> SanitizerResult = { _, _ -> success() },
        val holdDestination: Boolean = false
    ) : AutoCloseable {
        val aFile = File.createTempFile("sanitizer_a_", ".pdf", app.cacheDir)
        val bFile = File.createTempFile("sanitizer_b_", ".pdf", app.cacheDir)
        val a: Uri = Uri.fromFile(aFile)
        val b: Uri = Uri.fromFile(bFile)
        val initial = mutableStateOf<Uri?>(a)
        private val visible = mutableStateOf(true)
        private val store = ViewModelStore()
        private val resolver = app.contentResolver
        private val control = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        val tree: Uri = DocumentsContract.buildTreeDocumentUri(control.authority!!, "root")
        private val settings = app.getSharedPreferences("shrinkpdf_settings", Application.MODE_PRIVATE)
        private val oldFolder = settings.getString(OutputPolicy.DIRECTORY_KEY, null)
        var pendingCode: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.OpenDocumentTree)
                if (holdDestination) pendingCode = code else dispatchResult(code, Activity.RESULT_OK, Intent().setData(tree))
            }
        }
        init {
            resolver.call(control, "configure", null, null)
            resolver.call(control, "release", null, null)
            settings.edit().remove(OutputPolicy.DIRECTORY_KEY).commit()
            lateinit var model: MainViewModel
            compose.runOnUiThread { model = MainViewModel(app); store.put("ownership", model) }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme { if (visible.value) DocumentSanitizerContent(model, initial.value, {}, audit, clean) }
                }
            }
        }
        fun chooseB() = compose.runOnUiThread { initial.value = b }
        fun deliverDestination() = compose.runOnUiThread {
            registry.dispatchResult(requireNotNull(pendingCode), Activity.RESULT_OK, Intent().setData(tree))
        }
        fun creationWasAbandoned(): Boolean = requireNotNull(resolver.call(control, "state", null, null)).let {
            it.getInt("createdCount") == 1 && it.getInt("deletedCount") == 1
        }
        fun outputs() = DocumentFile.fromTreeUri(app, tree)!!.listFiles().filter { DocumentsContract.getDocumentId(it.uri) != "existing" }
        override fun close() {
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { store.clear() }
            outputs().forEach { OutputPolicy.unregisterFreshCopy(it.uri); it.delete() }
            settings.edit().apply { if (oldFolder == null) remove(OutputPolicy.DIRECTORY_KEY) else putString(OutputPolicy.DIRECTORY_KEY, oldFolder) }.commit()
            aFile.delete(); bFile.delete()
        }
    }

    @Test fun lateAuditCannotReplaceNextReportOrClearItsLoadingState() = lateAudit(failure = false)
    @Test fun lateAuditFailureCannotReplaceNextReportOrClearItsLoadingState() = lateAudit(failure = true)
    private fun lateAudit(failure: Boolean) {
        val startedA = CompletableDeferred<Unit>(); val releaseA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>(); val releaseB = CompletableDeferred<Unit>()
        var calls = 0
        val harness = Harness(audit = {
            if (++calls == 1) {
                startedA.complete(Unit)
                // Model a provider/backend that completes even after its caller was cancelled.
                try { withContext(NonCancellable) { releaseA.await(); if (failure) error("late A error") } }
                catch (_: kotlinx.coroutines.CancellationException) { }
                SanitizerAuditReport()
            } else {
                startedB.complete(Unit); releaseB.await()
                SanitizerAuditReport(threatsFound = 7, jsCount = 7, isClean = false)
            }
        })
        try {
            compose.waitUntil(10_000) { startedA.isCompleted }
            harness.chooseB()
            compose.onNodeWithText(harness.bFile.name).assertExists()
            releaseA.complete(Unit)
            compose.waitUntil(10_000) { startedB.isCompleted }
            compose.onNodeWithTag("sanitizer-audit-progress").assertExists()
            compose.onNodeWithText(app.getString(R.string.sanitizer_all_clean)).assertDoesNotExist()
            releaseB.complete(Unit)
            val report = app.getString(R.string.sanitizer_threats_detected, 7)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(report).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(harness.bFile.name).assertExists()
            compose.onNodeWithText(app.getString(R.string.sanitizer_all_clean)).assertDoesNotExist()
        } finally { releaseA.complete(Unit); releaseB.complete(Unit); harness.close() }
    }

    @Test fun saveUsesSnapshotAndLateCompletionIsNotReportedForNextDocument() {
        val saving = CompletableDeferred<SanitizerRequest>(); val releaseSave = CompletableDeferred<Unit>()
        val harness = Harness(audit = { SanitizerAuditReport() }, clean = { request, _ ->
            saving.complete(request)
            try { withContext(NonCancellable) { releaseSave.await() } }
            catch (_: kotlinx.coroutines.CancellationException) { }
            success()
        })
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ready()).fetchSemanticsNodes().size == 1 }
            compose.onAllNodes(isToggleable())[2].performScrollTo().performClick()
            compose.onNodeWithText(ready()).performScrollTo().performClick()
            compose.waitUntil(10_000) { saving.isCompleted }
            val request = kotlinx.coroutines.runBlocking { saving.await() }
            assertEquals(harness.a, request.source)
            assertFalse(request.purgeAttachments)
            compose.onNodeWithText(app.getString(R.string.select_pdf)).assertIsNotEnabled()
            harness.chooseB()
            releaseSave.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ready()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(harness.bFile.name).assertExists()
            compose.onNodeWithText(app.getString(R.string.integrity_sanitizer_selected_complete)).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.sanitizer_success)).assertDoesNotExist()
        } finally { releaseSave.complete(Unit); harness.close() }
    }

    @Test fun destinationReturnedForAnOldSelectionIsAbandonedWithoutSanitizingNewFile() {
        var cleaned = 0
        val harness = Harness(audit = { SanitizerAuditReport() }, clean = { _, _ -> cleaned++; success() }, holdDestination = true)
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ready()).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(ready()).performScrollTo().performClick()
            compose.waitUntil(10_000) { harness.pendingCode != null }
            compose.onNodeWithText(app.getString(R.string.select_pdf)).assertIsNotEnabled()
            harness.chooseB()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(ready()).fetchSemanticsNodes().size == 1 }
            harness.deliverDestination()
            compose.waitUntil(10_000) { harness.creationWasAbandoned() && harness.outputs().isEmpty() }
            assertEquals(0, cleaned)
            compose.onNodeWithText(harness.bFile.name).assertExists()
        } finally { harness.close() }
    }
    @Test fun rapidRepeatedSelectionCannotBypassOldWorkerCleanup() {
        val releaseA = CompletableDeferred<Unit>(); val startedA = CompletableDeferred<Unit>()
        var calls = 0
        val harness = Harness(audit = {
            if (++calls == 1) {
                startedA.complete(Unit)
                try { withContext(NonCancellable) { releaseA.await() } }
                catch (_: kotlinx.coroutines.CancellationException) { }
                SanitizerAuditReport()
            } else SanitizerAuditReport(threatsFound = 9, jsCount = 9, isClean = false)
        })
        try {
            compose.waitUntil(10_000) { startedA.isCompleted }
            harness.chooseB()
            compose.waitForIdle()
            compose.runOnUiThread { harness.initial.value = harness.a }
            compose.waitForIdle()
            assertEquals("Replacement must wait for the original worker owner, even after cancelling B", 1, calls)
            releaseA.complete(Unit)
            val report = app.getString(R.string.sanitizer_threats_detected, 9)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(report).fetchSemanticsNodes().size == 1 }
            assertEquals(2, calls)
            compose.onNodeWithText(app.getString(R.string.sanitizer_all_clean)).assertDoesNotExist()
        } finally { releaseA.complete(Unit); harness.close() }
    }
}
