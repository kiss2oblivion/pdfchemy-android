package com.pdfchemy.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.R
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.encryption.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class PickerOwnershipDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()
    private val store = ViewModelStore()
    private var previousVanguard: Boolean? = null
    @Before fun initialize() {
        PDFBoxResourceLoader.init(app)
        val prefs = app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
        previousVanguard = if (prefs.contains("vanguard_enabled")) prefs.getBoolean("vanguard_enabled", true) else null
    }
    @After fun cleanup() {
        compose.runOnUiThread { store.clear() }
        DocumentStager.releaseAll()
        files.forEach { it.delete() }
        app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE).edit().apply {
            previousVanguard?.let { putBoolean("vanguard_enabled", it) } ?: remove("vanguard_enabled")
        }.commit()
    }
    private fun source(encrypted: Boolean = false): Uri {
        val file = File.createTempFile("verify_picker", ".pdf", app.cacheDir)
        files.add(file)
        PDDocument().use { document ->
            document.addPage(PDPage())
            if (encrypted) document.protect(StandardProtectionPolicy("owner-secret", "reader-secret", AccessPermission()).apply { encryptionKeyLength = 128 })
            document.save(file)
        }
        return Uri.fromFile(file)
    }
    private fun registry(uris: List<Uri>): ActivityResultRegistryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, uris as O)
            }
        }
    }
    @Test fun actualPickerKeepsFiveBatchInputsAndClearReleasesThem() {
        app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE).edit().putBoolean("vanguard_enabled", false).commit()
        val uris = (1..5).map { source() }
        val owner = registry(uris)
        lateinit var model: MainViewModel
        compose.runOnUiThread { model = MainViewModel(app); store.put("batch", model) }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    val picker = rememberVanguardMultiplePdfPicker { selected -> model.onFilesSelected(app, selected) }
                    Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Select verification batch") }
                }
            }
        }
        compose.onNodeWithText("Select verification batch").performClick()
        compose.waitUntil(30000) { model.selectedFiles.value.size == 5 }
        val selected = model.selectedFiles.value.map { it.uri }
        repeat(10) { DocumentStager.stageStream(app, "eviction pressure $it".byteInputStream()) }
        selected.forEach { assertTrue("Picker callback returned an expired input", File(it.path!!).exists()) }
        compose.runOnUiThread { model.clearSelectedFiles() }
        compose.waitUntil(30000) { selected.none { File(it.path!!).exists() } }
    }
    @Test fun encryptedNavigationCallbackMustLeaveReadableInputForDestination() {
        app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE).edit().putBoolean("vanguard_enabled", true).commit()
        val owner = registry(listOf(source(true)))
        val destination = AtomicReference<com.pdfchemy.app.logic.PickerOutcome.Encrypted?>()
        val showPicker = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    if (showPicker.value) {
                    val picker = rememberVanguardMultiplePdfPicker(onNavigateToUnlock = { destination.set(it) }) { }
                    Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Select encrypted verification input") }
                    }
                }
            }
        }
        compose.onNodeWithText("Select encrypted verification input").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText(app.getString(R.string.vanguard_action_unlock)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.getString(R.string.vanguard_action_unlock)).performClick()
        compose.waitUntil(10000) { destination.get() != null }
        val handoff = destination.get()!!
        try {
            // Disposing the source screen must not release the destination's ownership.
            compose.runOnUiThread { showPicker.value = false }
            compose.waitForIdle()
            repeat(10) { DocumentStager.stageStream(app, "pressure $it".byteInputStream()) }
            assertTrue("Navigation input expired", File(handoff.stagedUri.path!!).exists())
        } finally { handoff.close() }
        assertFalse("Destination cleanup leaked the input", File(handoff.stagedUri.path!!).exists())
    }
}
