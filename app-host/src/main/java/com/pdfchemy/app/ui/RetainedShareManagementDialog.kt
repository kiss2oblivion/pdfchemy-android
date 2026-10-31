package com.pdfchemy.app.ui

import android.os.CancellationSignal
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.DocumentActions
import com.pdfchemy.app.logic.RetainedShareCleanupResult
import com.pdfchemy.app.logic.RetainedShareStorage
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RetainedShareManagementButton(modifier: Modifier = Modifier) {
    var show by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = modifier) {
        Text(stringResource(R.string.integrity_share_manage))
    }
    if (show) RetainedShareManagementDialog { show = false }
}

@Composable
private fun RetainedShareManagementDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext
    val scope = rememberCoroutineScope()
    var storage by remember { mutableStateOf<RetainedShareStorage?>(null) }
    var result by remember { mutableStateOf<RetainedShareCleanupResult?>(null) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var cancellation by remember { mutableStateOf<CancellationSignal?>(null) }

    DisposableEffect(Unit) {
        onDispose { cancellation?.cancel() }
    }

    suspend fun refresh() {
        busy = true
        failed = false
        try {
            storage = withContext(Dispatchers.IO) { DocumentActions.inspectRetainedShares(application) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            storage = null
            failed = true
        } finally {
            busy = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val eligible = storage?.entries.orEmpty().asSequence()
        .filter { it.protections.isEmpty() }
        .take(SecurityLimits.MAX_OUTPUT_FILES)
        .map { it.id }.toSet()

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.integrity_share_delete_confirm)) },
            text = { Text(stringResource(R.string.integrity_share_confirm, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    if (!busy) {
                        busy = true
                        confirm = false
                        result = null
                        failed = false
                        val signal = CancellationSignal()
                        val ids = selected
                        cancellation = signal
                        scope.launch {
                            try {
                                result = withContext(Dispatchers.IO) {
                                    DocumentActions.cleanupRetainedShares(application, ids, signal)
                                }
                                storage = withContext(Dispatchers.IO) {
                                    DocumentActions.inspectRetainedShares(application)
                                }
                            } catch (cancelled: CancellationException) {
                                signal.cancel()
                                throw cancelled
                            } catch (_: Exception) {
                                storage = null
                                failed = true
                            } finally {
                                cancellation = null
                                busy = false
                            }
                        }
                    }
                }, enabled = !busy) { Text(stringResource(R.string.integrity_share_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.integrity_share_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (busy) Text(stringResource(R.string.integrity_share_loading))
                    storage?.let { snapshot ->
                        Text(stringResource(R.string.integrity_share_usage,
                            snapshot.retainedCopyCount, Formatter.formatShortFileSize(context, snapshot.retainedCopyBytes),
                            snapshot.activeTransferCount, snapshot.incompleteCopyCount,
                            Formatter.formatShortFileSize(context, snapshot.totalBytes)))
                        Text(stringResource(R.string.integrity_share_protected))
                        if (eligible.isEmpty()) Text(stringResource(R.string.integrity_share_none))
                    }
                    result?.let { completed ->
                        Text(stringResource(R.string.integrity_share_result, completed.deletedCount,
                            completed.protected.size, completed.failed.size, completed.missing.size,
                            completed.unprocessed.size))
                        if (completed.cancelled) Text(stringResource(R.string.integrity_share_stopped))
                    }
                    if (failed) Text(stringResource(R.string.integrity_share_failure))
                    TextButton(onClick = {
                        if (!busy) { busy = true; scope.launch { refresh() } }
                    }, enabled = !busy) {
                        Text(stringResource(R.string.integrity_share_refresh))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = eligible; confirm = true },
                    enabled = !busy && eligible.isNotEmpty()) {
                    Text(stringResource(R.string.integrity_share_delete_inactive))
                }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}
