package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PickerOutcome
import com.pdfchemy.app.logic.VanguardPickerProcessor
import com.pdfchemy.app.logic.VanguardThreatResult
import com.pdfchemy.app.logic.VanguardInspectionFailure
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable Vanguard-protected file picker launcher.
 * Can be invoked with default PDF mime type: launch() or launch(arrayOf("application/pdf")).
 */
class VanguardPickerLauncher(
    private val onLaunch: (Array<String>) -> Unit
) {
    fun launch(input: Array<String> = arrayOf("application/pdf")) {
        onLaunch(input)
    }
}

/**
 * Composable hook providing a single-document PDF picker with integrated Vanguard Zero-Trust threat auditing,
 * animated scanning feedback overlay, and fail-closed security dialogs.
 */
@Composable
fun rememberVanguardPdfPicker(
    onNavigateToUnlock: ((Uri) -> Unit)? = null,
    onNavigateToRepair: ((Uri) -> Unit)? = null,
    onPdfSelected: (Uri) -> Unit
): VanguardPickerLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE) }
    var isVanguardEnabled by remember {
        mutableStateOf(prefs.getBoolean("vanguard_enabled", true))
    }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "vanguard_enabled") {
                isVanguardEnabled = prefs.getBoolean("vanguard_enabled", true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    var isScanning by remember { mutableStateOf(false) }
    var scanningFileName by remember { mutableStateOf<String?>(null) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var blockedReasonResId by remember { androidx.compose.runtime.mutableIntStateOf(R.string.vanguard_blocked_message_default) }
    var recoverySource by remember { mutableStateOf<Uri?>(null) }
    var showDamagedDialog by remember { mutableStateOf(false) }
    var damagedPendingUri by remember { mutableStateOf<Uri?>(null) }
    var inspectionFailure by remember { mutableStateOf(VanguardInspectionFailure.UNKNOWN) }
    var showProviderErrorDialog by remember { mutableStateOf(false) }
    var showEncryptedDialog by remember { mutableStateOf(false) }
    var encryptedPendingUri by remember { mutableStateOf<Uri?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { encryptedPendingUri?.let(com.pdfchemy.app.utils.DocumentStager::release) }
    }

    VanguardScanningOverlay(
        visible = isScanning,
        fileName = scanningFileName
    )

    fun startSelection(originalUri: Uri, forceInspection: Boolean = false) {
        if (!isScanning) {
            isScanning = true
            scope.launch {
                try {
                if (forceInspection || isVanguardEnabled) {
                    isScanning = true
                    scanningFileName = null
                    com.pdfchemy.app.utils.ProviderMetadata.load(context, originalUri)
                    scanningFileName = FileUtils.getFileName(context, originalUri)
                }
                    when (val outcome = VanguardPickerProcessor.processSingle(context, originalUri, forceInspection || isVanguardEnabled)) {
                        is PickerOutcome.Selected -> {
                            onPdfSelected(outcome.stagedUris.first())
                        }
                        is PickerOutcome.BlockedThreat -> {
                            recoverySource = originalUri
                            
                            blockedReasonResId = outcome.reasonResId
                            showBlockedDialog = true
                        }
                        is PickerOutcome.Encrypted -> {
                            recoverySource = originalUri
                            
                            encryptedPendingUri = outcome.stagedUri
                            showEncryptedDialog = true
                        }
                        is PickerOutcome.Damaged -> {
                            inspectionFailure = outcome.failure
                            damagedPendingUri = originalUri
                            showDamagedDialog = true
                        }
                        is PickerOutcome.ProviderError -> {
                            recoverySource = originalUri
                            
                            showProviderErrorDialog = true
                        }
                        is PickerOutcome.Cancelled -> {
                            // Cancelled - no error dialog
                        }
                    }
                } catch (_: CancellationException) {
                    // Staging or scope cancelled - no error dialog
                } catch (_: Exception) {
                    recoverySource = originalUri
                    showProviderErrorDialog = true
                } finally {
                    isScanning = false
                    scanningFileName = null
                }
            }
        }
    }

    if (showDamagedDialog) {
        damagedPendingUri?.let { failedUri ->
            VanguardInspectionRecoveryDialog(
                sourceUri = failedUri, failure = inspectionFailure,
                onDismiss = { showDamagedDialog = false; damagedPendingUri = null },
                onContinue = { replacement ->
                    showDamagedDialog = false
                    damagedPendingUri = null
                    startSelection(replacement, forceInspection = true)
                }
            )
        }
    }

    if (showBlockedDialog || showEncryptedDialog || showProviderErrorDialog) {
        recoverySource?.let { source ->
            fun clearRecovery() {
                encryptedPendingUri?.let { if (it != recoverySource) com.pdfchemy.app.utils.DocumentStager.release(it) }; encryptedPendingUri = null
                showBlockedDialog = false; showEncryptedDialog = false; showProviderErrorDialog = false
                recoverySource = null
            }
            if (showProviderErrorDialog) {
                VanguardInspectionRecoveryDialog(source, VanguardInspectionFailure.ACCESS_FAILED,
                    onDismiss = { clearRecovery(); Unit },
                    onContinue = { replacement -> clearRecovery(); startSelection(replacement, forceInspection = true) })
            } else {
                VanguardProtectedRecoveryDialog(
                    sourceUri = source, encrypted = showEncryptedDialog, blockedReasonResId = blockedReasonResId,
                    onDismiss = { clearRecovery(); Unit },
                    onContinue = { replacement -> clearRecovery(); startSelection(replacement, forceInspection = true) }
                )
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { startSelection(it) }
    }

    return remember(launcher) { VanguardPickerLauncher { types -> if (!isScanning && !showDamagedDialog && !showBlockedDialog && !showEncryptedDialog && !showProviderErrorDialog) launcher.launch(types) } }
}

/**
 * Composable hook providing a multiple-document PDF picker with integrated Vanguard Zero-Trust threat auditing,
 * animated scanning feedback overlay, and fail-closed security dialogs.
 */
@Composable
// Unlock navigation takes ownership of the closeable outcome. The destination must close it
// after retaining the input in its own model or when the navigation is abandoned.
fun rememberVanguardMultiplePdfPicker(
    onNavigateToUnlock: ((PickerOutcome.Encrypted) -> Unit)? = null,
    onNavigateToRepair: ((Uri) -> Unit)? = null,
    onPdfsSelected: (List<Uri>) -> Unit
): VanguardPickerLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE) }
    var isVanguardEnabled by remember {
        mutableStateOf(prefs.getBoolean("vanguard_enabled", true))
    }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "vanguard_enabled") {
                isVanguardEnabled = prefs.getBoolean("vanguard_enabled", true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    var isScanning by remember { mutableStateOf(false) }
    var scanningFileName by remember { mutableStateOf<String?>(null) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var blockedReasonResId by remember { androidx.compose.runtime.mutableIntStateOf(R.string.vanguard_blocked_message_default) }
    var recoverySource by remember { mutableStateOf<Uri?>(null) }
    var showDamagedDialog by remember { mutableStateOf(false) }
    var damagedPendingUri by remember { mutableStateOf<Uri?>(null) }
    var inspectionFailure by remember { mutableStateOf(VanguardInspectionFailure.UNKNOWN) }
    var pendingBatch by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showProviderErrorDialog by remember { mutableStateOf(false) }
    var showEncryptedDialog by remember { mutableStateOf(false) }
    var encryptedPendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingOutcome by remember { mutableStateOf<PickerOutcome.Encrypted?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { pendingOutcome?.close() }
    }

    VanguardScanningOverlay(
        visible = isScanning,
        fileName = scanningFileName
    )

    fun startSelection(uris: List<Uri>, forceInspection: Boolean = false) {
        if (uris.isNotEmpty() && !isScanning) {
            isScanning = true
            scope.launch {
                try {
                    var currentItemUri: Uri? = uris.firstOrNull()
                    val outcome = VanguardPickerProcessor.processMultiple(
                        context = context,
                        uris = uris,
                        isVanguardEnabled = forceInspection || isVanguardEnabled,
                        onItemScanning = { itemUri ->
                            currentItemUri = itemUri
                            if (forceInspection || isVanguardEnabled) {
                                isScanning = true
                                scanningFileName = null
                                com.pdfchemy.app.utils.ProviderMetadata.load(context, itemUri)
                                scanningFileName = FileUtils.getFileName(context, itemUri)
                            }
                        }
                    )
                    when (outcome) {
                        is PickerOutcome.Selected -> {
                            outcome.use { onPdfsSelected(it.stagedUris) }
                        }
                        is PickerOutcome.BlockedThreat -> {
                            recoverySource = currentItemUri
                            pendingBatch = uris
                            blockedReasonResId = outcome.reasonResId
                            showBlockedDialog = true
                        }
                        is PickerOutcome.Encrypted -> {
                            recoverySource = currentItemUri
                            pendingBatch = uris
                            pendingOutcome?.close()
                            pendingOutcome = outcome
                            encryptedPendingUri = outcome.stagedUri
                            showEncryptedDialog = true
                        }
                        is PickerOutcome.Damaged -> {
                            inspectionFailure = outcome.failure
                            pendingBatch = uris
                            damagedPendingUri = currentItemUri
                            showDamagedDialog = true
                        }
                        is PickerOutcome.ProviderError -> {
                            recoverySource = currentItemUri
                            pendingBatch = uris
                            showProviderErrorDialog = true
                        }
                        is PickerOutcome.Cancelled -> {
                            // Cancelled - no error dialog
                        }
                    }
                } catch (_: CancellationException) {
                    // Staging or scope cancelled - no error dialog
                } finally {
                    isScanning = false
                    scanningFileName = null
                }
            }
        }
    }

    if (showDamagedDialog) {
        damagedPendingUri?.let { failedUri ->
            VanguardInspectionRecoveryDialog(
                sourceUri = failedUri, failure = inspectionFailure,
                onDismiss = { showDamagedDialog = false; damagedPendingUri = null; pendingBatch = emptyList() },
                onContinue = { replacement ->
                    showDamagedDialog = false
                    damagedPendingUri = null
                    val batch = pendingBatch.map { if (it == failedUri) replacement else it }
                    pendingBatch = emptyList()
                    startSelection(batch, forceInspection = true)
                }
            )
        }
    }

    if (showBlockedDialog || showEncryptedDialog || showProviderErrorDialog) {
        recoverySource?.let { source ->
            fun clearRecovery() {
                pendingOutcome?.close(); pendingOutcome = null; encryptedPendingUri = null
                showBlockedDialog = false; showEncryptedDialog = false; showProviderErrorDialog = false
                recoverySource = null
            }
            if (showProviderErrorDialog) {
                VanguardInspectionRecoveryDialog(source, VanguardInspectionFailure.ACCESS_FAILED,
                    onDismiss = { clearRecovery(); pendingBatch = emptyList() },
                    onContinue = { replacement -> clearRecovery(); val batch = pendingBatch.map { if (it == source) replacement else it }; pendingBatch = emptyList(); startSelection(batch, forceInspection = true) })
            } else {
                VanguardProtectedRecoveryDialog(
                    sourceUri = source, encrypted = showEncryptedDialog, blockedReasonResId = blockedReasonResId,
                    onDismiss = { clearRecovery(); pendingBatch = emptyList() },
                    onContinue = { replacement -> clearRecovery(); val batch = pendingBatch.map { if (it == source) replacement else it }; pendingBatch = emptyList(); startSelection(batch, forceInspection = true) }
                )
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        startSelection(uris)
    }

    return remember(launcher) { VanguardPickerLauncher { types -> if (!isScanning && !showDamagedDialog && !showBlockedDialog && !showEncryptedDialog && !showProviderErrorDialog) launcher.launch(types) } }
}
