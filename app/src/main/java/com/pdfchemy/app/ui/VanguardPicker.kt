package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
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

import com.pdfchemy.app.logic.VanguardThreatResult
import com.pdfchemy.app.utils.FileUtils
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
                    text = stringResource(R.string.vanguard_blocked_message),
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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { originalUri: Uri? ->
        if (originalUri != null) {
            if (isVanguardEnabled) {
                isScanning = true
                scanningFileName = FileUtils.getFileName(context, originalUri)
                scope.launch {
                    try {
                        val stagedUri = withContext(Dispatchers.IO) {
                            com.pdfchemy.app.utils.DocumentStager.stageDocument(context, originalUri)
                        }
                        val threat = com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, stagedUri)
                        withContext(Dispatchers.Main) {
                            when (threat) {
                                is VanguardThreatResult.Clean -> {
                                    onPdfSelected(stagedUri)
                                }
                                is VanguardThreatResult.EncryptedCannotVerify -> {
                                    encryptedPendingUri = stagedUri
                                    showEncryptedDialog = true
                                }
                                is VanguardThreatResult.ExecutableThreat,
                                is VanguardThreatResult.ParseFailed -> {
                                    showBlockedDialog = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showBlockedDialog = true
                        }
                    } finally {
                        isScanning = false
                        scanningFileName = null
                    }
                }
            } else {
                onPdfSelected(originalUri)
            }
        }
    }

    return remember(launcher) { VanguardPickerLauncher { types -> launcher.launch(types) } }
}

/**
 * Composable hook providing a multiple-document PDF picker with integrated Vanguard Zero-Trust threat auditing,
 * animated scanning feedback overlay, and fail-closed security dialogs.
 */
@Composable
fun rememberVanguardMultiplePdfPicker(
    onNavigateToUnlock: ((Uri) -> Unit)? = null,
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
                    text = stringResource(R.string.vanguard_blocked_message),
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

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            if (isVanguardEnabled) {
                scope.launch {
                    var allClean = true
                    val stagedUris = mutableListOf<Uri>()
                    for (originalUri in uris) {
                        isScanning = true
                        scanningFileName = FileUtils.getFileName(context, originalUri)
                        try {
                            val stagedUri = withContext(Dispatchers.IO) {
                                com.pdfchemy.app.utils.DocumentStager.stageDocument(context, originalUri)
                            }
                            stagedUris.add(stagedUri)
                            val threat = com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, stagedUri)
                            var stopBatch = false
                            withContext(Dispatchers.Main) {
                                when (threat) {
                                    is VanguardThreatResult.Clean -> {
                                        // verified clean, proceed to next
                                    }
                                    is VanguardThreatResult.EncryptedCannotVerify -> {
                                        encryptedPendingUri = stagedUri
                                        showEncryptedDialog = true
                                        allClean = false
                                        stopBatch = true
                                    }
                                    is VanguardThreatResult.ExecutableThreat,
                                    is VanguardThreatResult.ParseFailed -> {
                                        showBlockedDialog = true
                                        allClean = false
                                        stopBatch = true
                                    }
                                }
                            }
                            if (stopBatch) break
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                showBlockedDialog = true
                                allClean = false
                            }
                            break
                        } finally {
                            isScanning = false
                            scanningFileName = null
                        }
                    }
                    if (allClean) {
                        withContext(Dispatchers.Main) {
                            onPdfsSelected(stagedUris)
                        }
                    }
                }
            } else {
                onPdfsSelected(uris)
            }
        }
    }

    return remember(launcher) { VanguardPickerLauncher { types -> launcher.launch(types) } }
}
