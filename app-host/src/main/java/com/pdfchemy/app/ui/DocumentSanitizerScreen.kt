package com.pdfchemy.app.ui

import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.FileUtil

import com.pdfchemy.app.logic.SanitizerAuditReport
import com.pdfchemy.app.logic.SanitizerResult
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentSanitizerScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    DocumentSanitizerContent(viewModel, initialPdfUri, onBack,
        audit = { com.pdfchemy.app.logic.PdfSanitizerEngine.auditDocumentThreats(context, it) },
        sanitize = { request, destination ->
            com.pdfchemy.app.logic.PdfSanitizerEngine.sanitizeDocument(context, request.source, destination,
                request.purgeJs, request.purgeActions, request.purgeMetadata, request.purgeAttachments)
        })
}

internal data class SanitizerRequest(val source: Uri, val generation: Long,
    val purgeJs: Boolean, val purgeActions: Boolean, val purgeMetadata: Boolean, val purgeAttachments: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentSanitizerContent(
    viewModel: MainViewModel,
    initialPdfUri: Uri?,
    onBack: () -> Unit,
    audit: suspend (Uri) -> SanitizerAuditReport,
    sanitize: suspend (SanitizerRequest, Uri) -> SanitizerResult
) {
    SecureScreenContent()
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val continuityUri by viewModel.continuityDocumentUri.collectAsState()
    val effectiveInitialPdfUri = initialPdfUri ?: continuityUri
    var selectedPdfUri by remember { mutableStateOf<Uri?>(effectiveInitialPdfUri) }
    var auditReport by remember { mutableStateOf<SanitizerAuditReport?>(null) }
    var isAuditing by remember { mutableStateOf(false) }
    var isSanitizing by remember { mutableStateOf(false) }
    var sanitizeResult by remember { mutableStateOf<SanitizerResult?>(null) }
    var sanitizeFailed by remember { mutableStateOf(false) }

    var purgeJs by remember { mutableStateOf(true) }
    var purgeActions by remember { mutableStateOf(true) }
    var purgeMetadata by remember { mutableStateOf(true) }
    var purgeAttachments by remember { mutableStateOf(true) }

    val requests = remember { com.pdfchemy.app.logic.LatestRequest() }
    val workerAccess = remember { kotlinx.coroutines.sync.Mutex() }
    var generation by remember { mutableStateOf(0L) }
    var auditJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var sanitizeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pendingSave by remember { mutableStateOf<SanitizerRequest?>(null) }

    fun runAudit(uri: Uri) {
        val ticket = requests.begin()
        generation = ticket
        auditJob?.cancel()
        sanitizeJob?.cancel()
        pendingSave = null
        selectedPdfUri = uri
        isAuditing = true
        isSanitizing = false
        auditReport = null
        sanitizeResult = null
        sanitizeFailed = false
        auditJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                // A cancelled intermediate selection cannot bypass an older operation's cleanup.
                val report = workerAccess.withLock { audit(uri) }
                if (requests.isCurrent(ticket)) auditReport = report
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLogger.e("Audit failed", e)
                if (requests.isCurrent(ticket)) auditReport = SanitizerAuditReport(threatsFound = 1, isClean = false, parseFailed = true)
            } finally {
                if (requests.isCurrent(ticket)) isAuditing = false
            }
        }
    }

    DisposableEffect(requests) {
        onDispose { requests.invalidate(); auditJob?.cancel(); sanitizeJob?.cancel() }
    }
    LaunchedEffect(effectiveInitialPdfUri) {
        effectiveInitialPdfUri?.let(::runAudit)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::runAudit)
    }

    val saveFileLauncher = rememberPreferredDocumentCreator("application/pdf") { destination ->
        val request = pendingSave
        pendingSave = null
        if (destination != null) {
            if (request == null || !requests.isCurrent(request.generation) || selectedPdfUri != request.source) {
                scope.launch(Dispatchers.IO) { com.pdfchemy.app.logic.OutputPolicy.deleteIfFresh(context, destination) }
            } else {
                isSanitizing = true
                sanitizeResult = null
                sanitizeFailed = false
                sanitizeJob = scope.launch(Dispatchers.Main.immediate) {
                    try {
                        val result = workerAccess.withLock { sanitize(request, destination) }
                        if (result.isSuccess) com.pdfchemy.app.logic.OutputPolicy.markPublished(destination)
                        if (requests.isCurrent(request.generation)) {
                            if (result.isSuccess) {
                                sanitizeResult = result
                                Toast.makeText(context, context.getString(R.string.sanitizer_success), Toast.LENGTH_SHORT).show()
                            } else sanitizeFailed = true
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        AppLogger.e("Sanitizing failed", e)
                        if (requests.isCurrent(request.generation)) sanitizeFailed = true
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            com.pdfchemy.app.logic.OutputPolicy.deleteIfFresh(context, destination)
                        }
                        if (requests.isCurrent(request.generation)) isSanitizing = false
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sanitizer_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    val report = auditReport
                    if (report != null) {
                        IconButton(
                            onClick = {
                                val summary = buildString {
                                    appendLine("Status: ${when {
                                         report.isEncrypted -> "Encrypted; inspection unverified"
                                         report.parseFailed -> "Inspection incomplete: ${report.failure?.name ?: "UNKNOWN"}"
                                         report.isClean -> "No detected active content"
                                         else -> "${report.threatsFound} threats detected"
                                     }}")
                                    appendLine("JavaScript Triggers: ${report.jsCount}")
                                    appendLine("Launch Actions: ${report.launchActionsCount}")
                                    appendLine("Other Actions: ${report.otherActionsCount}")
                                    append("Attachments: ${report.attachmentCount}")
                                }
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Sanitizer Audit Report", summary))
                                Toast.makeText(context, context.getString(R.string.msg_audit_report_copied), Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.desc_copy_audit_report))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                enabled = !isSanitizing && pendingSave == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Security, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.select_pdf))
            }

            selectedPdfUri?.let { uri ->
                val fileName = rememberDocumentMetadata(uri).name ?: "Document.pdf"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(fileName, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }

            if (isAuditing) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag("sanitizer-audit-progress"))
                }
            } else if (auditReport != null) {
                val report = auditReport!!

                // Audit Summary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (report.isClean)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (report.isClean) Icons.Rounded.VerifiedUser else Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = if (report.isClean) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                when {
                                    report.isEncrypted -> stringResource(R.string.vanguard_encrypted_title)
                                    report.parseFailed -> stringResource(R.string.vanguard_damaged_title)
                                    report.isClean -> stringResource(R.string.sanitizer_all_clean)
                                    else -> stringResource(R.string.sanitizer_threats_detected, report.threatsFound)
                                },
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        if (report.jsCount > 0) Text(stringResource(R.string.sanitizer_js_threats, report.jsCount), style = MaterialTheme.typography.bodyMedium)
                        if (report.launchActionsCount > 0) Text(stringResource(R.string.sanitizer_action_threats, report.launchActionsCount), style = MaterialTheme.typography.bodyMedium)
                        if (report.otherActionsCount > 0) Text(stringResource(R.string.sanitizer_other_action_threats, report.otherActionsCount), style = MaterialTheme.typography.bodyMedium)
                        if (report.attachmentCount > 0) Text(stringResource(R.string.sanitizer_attachments, report.attachmentCount), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Sterilization Options
                Text(
                    stringResource(R.string.sanitizer_audit_summary),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.sanitizer_purge_js), modifier = Modifier.weight(1f).padding(end = 12.dp))
                            Switch(checked = purgeJs, onCheckedChange = { purgeJs = it }, enabled = !isSanitizing && pendingSave == null)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.sanitizer_purge_actions), modifier = Modifier.weight(1f).padding(end = 12.dp))
                            Switch(checked = purgeActions, onCheckedChange = { purgeActions = it }, enabled = !isSanitizing && pendingSave == null)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.sanitizer_purge_embedded), modifier = Modifier.weight(1f).padding(end = 12.dp))
                            Switch(checked = purgeAttachments, onCheckedChange = { purgeAttachments = it }, enabled = !isSanitizing && pendingSave == null)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.sanitizer_purge_metadata), modifier = Modifier.weight(1f).padding(end = 12.dp))
                            Switch(checked = purgeMetadata, onCheckedChange = { purgeMetadata = it }, enabled = !isSanitizing && pendingSave == null)
                        }
                    }
                }

                if (sanitizeFailed) {
                    Text(
                        stringResource(R.string.integrity_sanitizer_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                }

                sanitizeResult?.takeIf { it.isSuccess }?.let {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.sanitizer_success), fontWeight = FontWeight.Bold)
                            }
                            Text(
                                stringResource(R.string.integrity_sanitizer_selected_complete),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val source = selectedPdfUri ?: return@Button
                        pendingSave = SanitizerRequest(source, generation, purgeJs, purgeActions, purgeMetadata, purgeAttachments)
                        val suggestedName = FileUtil.generateSuggestedName(context, source, "sanitized")
                        saveFileLauncher.launch(suggestedName)
                    },
                    enabled = !isSanitizing && pendingSave == null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (isSanitizing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onError)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.sanitizer_action_sanitize), color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}
