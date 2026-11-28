package com.pdfchemy.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.*
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.*

internal fun VanguardInspectionFailure.explanationRes(): Int = when (this) {
    VanguardInspectionFailure.PDF_READ_FAILED -> R.string.vanguard_reason_read
    VanguardInspectionFailure.GRAPH_LIMIT -> R.string.vanguard_reason_limit
    VanguardInspectionFailure.ACTIVE_CONTENT_INSPECTION_FAILED -> R.string.vanguard_reason_active
    VanguardInspectionFailure.METADATA_INSPECTION_FAILED -> R.string.vanguard_reason_metadata
    VanguardInspectionFailure.WORKER_BUSY -> R.string.vanguard_reason_busy
    VanguardInspectionFailure.WORKER_TIMEOUT -> R.string.vanguard_reason_timeout
    VanguardInspectionFailure.WORKER_UNAVAILABLE -> R.string.vanguard_reason_worker
    VanguardInspectionFailure.ACCESS_FAILED -> R.string.vanguard_reason_access
    VanguardInspectionFailure.INPUT_SIZE_LIMIT -> R.string.vanguard_reason_input_size
    VanguardInspectionFailure.STAGING_CAPACITY -> R.string.vanguard_reason_staging_capacity
    VanguardInspectionFailure.UNKNOWN -> R.string.vanguard_reason_unknown
}

internal fun VanguardInspectionFailure.canAttemptStructuralRecovery(): Boolean =
    this in setOf(VanguardInspectionFailure.PDF_READ_FAILED, VanguardInspectionFailure.ACTIVE_CONTENT_INSPECTION_FAILED,
        VanguardInspectionFailure.METADATA_INSPECTION_FAILED, VanguardInspectionFailure.UNKNOWN)

@Composable
internal fun VanguardInspectionRecoveryDialog(
    sourceUri: Uri,
    failure: VanguardInspectionFailure,
    onDismiss: () -> Unit,
    onContinue: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val continueLatest by rememberUpdatedState(onContinue)
    var operationJob by remember(sourceUri, failure) { mutableStateOf<Job?>(null) }
    var diagnosing by remember(sourceUri, failure) { mutableStateOf(false) }
    var repairing by remember(sourceUri, failure) { mutableStateOf(false) }
    var choosingDestination by remember(sourceUri, failure) { mutableStateOf(false) }
    var diagnostic by remember(sourceUri, failure) { mutableStateOf<RepairDiagnostic?>(null) }
    var diagnosisFailed by remember(sourceUri, failure) { mutableStateOf(false) }
    var repairFailed by remember(sourceUri, failure) { mutableStateOf(false) }
    var showDetails by remember(sourceUri, failure) { mutableStateOf(false) }
    val busy = diagnosing || repairing || choosingDestination
    val allowRepair = failure.canAttemptStructuralRecovery() && diagnostic?.isEncrypted == false
    val canDiagnose = failure !in setOf(VanguardInspectionFailure.ACCESS_FAILED,
        VanguardInspectionFailure.INPUT_SIZE_LIMIT, VanguardInspectionFailure.STAGING_CAPACITY)

    val save = rememberPreferredDocumentDestinationCreator("application/pdf") { destination ->
        choosingDestination = false
        if (destination != null) {
            repairing = true
            operationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    check(destination.isFreshCopy)
                    // The worker verifies its rebuilt scratch PDF before the normal gateway publication.
                    val result = PdfGateway.executeEngineTyped<RepairApplyContract>(context, "REPAIR_APPLY",
                        sourceUri, destination.uri, """{"requireVanguardClean":true}""")
                    check(result.success && result.recoveredPages > 0)
                    currentCoroutineContext().ensureActive()
                    // The original selection path inspects again, even if the setting changed.
                    continueLatest(destination.uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    repairFailed = true
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        OutputPolicy.deleteIfFresh(context, destination.uri)
                    }
                    repairing = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.vanguard_damaged_title)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(failure.explanationRes()), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.vanguard_damaged_message))
                Text(stringResource(when (failure) {
                    VanguardInspectionFailure.ACCESS_FAILED -> R.string.vanguard_access_next
                    VanguardInspectionFailure.INPUT_SIZE_LIMIT -> R.string.vanguard_input_next
                    VanguardInspectionFailure.STAGING_CAPACITY -> R.string.vanguard_capacity_next
                    else -> R.string.vanguard_recovery_next
                }), style = MaterialTheme.typography.bodySmall)
                if (diagnosing || repairing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (diagnosing) R.string.diagnosing_document else R.string.vanguard_recovery_rebuilding))
                }
                diagnostic?.let { report ->
                    HorizontalDivider()
                    Text(stringResource(R.string.label_diagnostic_report), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.vanguard_recovery_header, if (report.hasValidHeader)
                        stringResource(R.string.vanguard_recovery_found) else stringResource(R.string.vanguard_recovery_not_found)))
                    Text(stringResource(R.string.vanguard_recovery_eof, if (report.hasValidEof)
                        stringResource(R.string.vanguard_recovery_found) else stringResource(R.string.vanguard_recovery_not_found)))
                    Text(stringResource(R.string.vanguard_recovery_pages, report.recoveredPages))
                    RecoveryDiagnosticMetrics(report)
                    Text(stringResource(when {
                        report.isEncrypted -> R.string.vanguard_recovery_password
                        report.recoveredPages > 0 -> R.string.vanguard_recovery_readable
                        else -> R.string.vanguard_recovery_undetermined
                    }), style = MaterialTheme.typography.bodySmall)
                }
                if (diagnosisFailed) Text(stringResource(R.string.vanguard_recovery_diagnosis_failed), color = MaterialTheme.colorScheme.error)
                if (repairFailed) Text(stringResource(R.string.vanguard_recovery_repair_failed), color = MaterialTheme.colorScheme.error)
                if (canDiagnose) {
                    OutlinedButton(enabled = !busy, onClick = {
                        diagnosing = true; diagnosisFailed = false; repairFailed = false; diagnostic = null
                        operationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                diagnostic = PdfRepairEngine.diagnosePdf(context, sourceUri).getOrThrow()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) { diagnosisFailed = true }
                            finally { diagnosing = false }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vanguard_recovery_diagnose)) }
                }
                if (allowRepair) {
                    Text(stringResource(R.string.vanguard_recovery_copy_help), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = {
                        choosingDestination = true; repairFailed = false
                        save.launch("recovered.pdf")
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vanguard_recovery_repair)) }
                }
                TextButton(enabled = !busy, onClick = { showDetails = !showDetails }) {
                    Text(stringResource(R.string.vanguard_recovery_details))
                }
                if (showDetails) {
                    Text("VANGUARD / " + failure.name, style = MaterialTheme.typography.bodySmall)
                    if (failure == VanguardInspectionFailure.GRAPH_LIMIT) {
                        Text(stringResource(R.string.vanguard_recovery_limits, SecurityLimits.MAX_GRAPH_NODES,
                            SecurityLimits.MAX_GRAPH_DEPTH), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = !busy, onClick = {
                        val report = buildString {
                            appendLine("VANGUARD inspection incomplete")
                            appendLine("Code: " + failure.name)
                            appendLine("App version: " + com.pdfchemy.app.BuildConfig.VERSION_NAME)
                            appendLine("App build: " + com.pdfchemy.app.BuildConfig.VERSION_CODE)
                            appendLine("Per-file input bytes limit: " + SecurityLimits.MAX_PDF_FILESIZE)
                            appendLine("Aggregate staged bytes limit: " + SecurityLimits.MAX_BATCH_INPUT_BYTES)
                            appendLine("Staged document count limit: " + SecurityLimits.MAX_STAGED_DOCUMENTS)
                            appendLine("Graph nodes limit: " + SecurityLimits.MAX_GRAPH_NODES)
                            appendLine("Graph depth limit: " + SecurityLimits.MAX_GRAPH_DEPTH)
                            appendLine("Worker deadline ms: " + SecurityLimits.WORKER_DEADLINE_MS)
                            diagnostic?.let {
                                appendLine("Header marker: " + it.hasValidHeader)
                                appendLine("EOF marker: " + it.hasValidEof)
                                appendLine("Readable page count: " + it.recoveredPages)
                                appendLine("Encrypted: " + it.isEncrypted)
                                it.sourceBytes?.let { value -> appendLine("Staged input bytes: $value") }
                                it.diagnosticElapsedMs?.let { value -> appendLine("Worker diagnosis ms: $value") }
                                it.parserOpened?.let { value -> appendLine("Parser completed: $value") }
                                it.pooledObjects?.let { value -> appendLine("Parsed object pool entries: $value") }
                            }
                        }
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            ?.setPrimaryClip(ClipData.newPlainText("PDFchemy Vanguard", report))
                        Toast.makeText(context, R.string.msg_diagnostic_report_copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.desc_copy_diagnostic_report)) }
                }
            }
        },
        confirmButton = {
            Button(enabled = !busy && failure != VanguardInspectionFailure.INPUT_SIZE_LIMIT, onClick = { continueLatest(sourceUri) }) {
                Text(stringResource(R.string.vanguard_recovery_retry))
            }
        },
        dismissButton = {
            TextButton(enabled = !choosingDestination, onClick = {
                if (diagnosing || repairing) operationJob?.cancel() else onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** These observations describe the staged PDF, not a conformance certificate. */
@Composable
internal fun RecoveryDiagnosticMetrics(report: RepairDiagnostic) {
    report.sourceBytes?.let { Text(stringResource(R.string.vanguard_diagnostic_bytes, it)) }
    report.diagnosticElapsedMs?.let { Text(stringResource(R.string.vanguard_diagnostic_duration, it)) }
    report.parserOpened?.let {
        Text(stringResource(if (it) R.string.vanguard_diagnostic_parser_opened else R.string.vanguard_diagnostic_parser_incomplete),
            style = MaterialTheme.typography.bodySmall)
    }
    report.pooledObjects?.let {
        Text(stringResource(R.string.vanguard_diagnostic_pool, it), style = MaterialTheme.typography.bodySmall)
    }
}
