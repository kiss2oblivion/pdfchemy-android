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
    var showDamagedDialog by remember { mutableStateOf(false) }
    var damagedPendingUri by remember { mutableStateOf<Uri?>(null) }
    var inspectionFailure by remember { mutableStateOf(VanguardInspectionFailure.UNKNOWN) }
    var showProviderErrorDialog by remember { mutableStateOf(false) }
    var showEncryptedDialog by remember { mutableStateOf(false) }
    var encryptedPendingUri by remember { mutableStateOf<Uri?>(null) }

    VanguardScanningOverlay(
        visible = isScanning,
        fileName = scanningFileName
    )

    if (showBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_blocked_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = stringResource(blockedReasonResId),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { showBlockedDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    fun startSelection(originalUri: Uri, forceInspection: Boolean = false) {
        if (!isScanning) {
            isScanning = true
            scope.launch {
                if (forceInspection || isVanguardEnabled) {
                    isScanning = true
                    scanningFileName = null
                    com.pdfchemy.app.utils.ProviderMetadata.load(context, originalUri)
                    scanningFileName = FileUtils.getFileName(context, originalUri)
                }
                try {
                    when (val outcome = VanguardPickerProcessor.processSingle(context, originalUri, forceInspection || isVanguardEnabled)) {
                        is PickerOutcome.Selected -> {
                            onPdfSelected(outcome.stagedUris.first())
                        }
                        is PickerOutcome.BlockedThreat -> {
                            blockedReasonResId = outcome.reasonResId
                            showBlockedDialog = true
                        }
                        is PickerOutcome.Encrypted -> {
                            encryptedPendingUri = outcome.stagedUri
                            showEncryptedDialog = true
                        }
                        is PickerOutcome.Damaged -> {
                            inspectionFailure = outcome.failure
                            damagedPendingUri = originalUri
                            showDamagedDialog = true
                        }
                        is PickerOutcome.ProviderError -> {
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
                onDismiss = { showDamagedDialog = false; damagedPendingUri = null },
                onContinue = { replacement ->
                    showDamagedDialog = false
                    damagedPendingUri = null
                    startSelection(replacement, forceInspection = true)
                }
            )
        }
    }

    if (showProviderErrorDialog) {
        AlertDialog(
            onDismissRequest = { showProviderErrorDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_provider_error_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_provider_error_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { showProviderErrorDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (showEncryptedDialog) {
        AlertDialog(
            onDismissRequest = {
                showEncryptedDialog = false
                encryptedPendingUri = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                if (onNavigateToUnlock != null && encryptedPendingUri != null) {
                    Button(
                        onClick = {
                            val targetUri = encryptedPendingUri
                            showEncryptedDialog = false
                            encryptedPendingUri = null
                            if (targetUri != null) {
                                onNavigateToUnlock(targetUri)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.vanguard_action_unlock))
                    }
                } else {
                    Button(
                        onClick = {
                            showEncryptedDialog = false
                            encryptedPendingUri = null
                        }
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = if (onNavigateToUnlock != null && encryptedPendingUri != null) {
                {
                    TextButton(
                        onClick = {
                            showEncryptedDialog = false
                            encryptedPendingUri = null
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else null
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { startSelection(it) }
    }

    return remember(launcher) { VanguardPickerLauncher { types -> if (!isScanning && !showDamagedDialog) launcher.launch(types) } }
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

    if (showBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_blocked_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = stringResource(blockedReasonResId),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { showBlockedDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

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
                            blockedReasonResId = outcome.reasonResId
                            showBlockedDialog = true
                        }
                        is PickerOutcome.Encrypted -> {
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

    if (showProviderErrorDialog) {
        AlertDialog(
            onDismissRequest = { showProviderErrorDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_provider_error_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_provider_error_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { showProviderErrorDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (showEncryptedDialog) {
        AlertDialog(
            onDismissRequest = {
                showEncryptedDialog = false
                pendingOutcome?.close(); pendingOutcome = null
                encryptedPendingUri = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                if (onNavigateToUnlock != null && encryptedPendingUri != null) {
                    Button(
                        onClick = {
                            val targetUri = encryptedPendingUri
                            showEncryptedDialog = false
                            encryptedPendingUri = null
                            if (targetUri != null) {
                                val handoff = pendingOutcome
                                pendingOutcome = null
                                if (handoff != null) {
                                    try { onNavigateToUnlock(handoff) }
                                    catch (error: Throwable) { handoff.close(); throw error }
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.vanguard_action_unlock))
                    }
                } else {
                    Button(
                        onClick = {
                            showEncryptedDialog = false
                            pendingOutcome?.close(); pendingOutcome = null
                            encryptedPendingUri = null
                        }
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = if (onNavigateToUnlock != null && encryptedPendingUri != null) {
                {
                    TextButton(
                        onClick = {
                            showEncryptedDialog = false
                            pendingOutcome?.close(); pendingOutcome = null
                            encryptedPendingUri = null
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else null
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        startSelection(uris)
    }

    return remember(launcher) { VanguardPickerLauncher { types -> if (!isScanning && !showDamagedDialog) launcher.launch(types) } }
}
