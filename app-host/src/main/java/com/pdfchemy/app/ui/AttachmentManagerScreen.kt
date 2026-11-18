package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PdfAttachment
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.logic.AttachmentListContract
import org.json.JSONArray
import org.json.JSONObject
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

private data class AttachmentAction(val source: Uri, val generation: Long,
    val attachmentId: String? = null, val extra: Uri? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentManagerScreen(
    viewModel: MainViewModel,
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    val continuityUri by viewModel.continuityDocumentUri.collectAsState()
    val effectiveInitialUri = initialUri ?: continuityUri
    var selectedPdfUri by remember { mutableStateOf<Uri?>(effectiveInitialUri) }
    var attachments by remember { mutableStateOf<List<PdfAttachment>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var targetRemoveAttachment by remember { mutableStateOf<PdfAttachment?>(null) }
    var showRemoveConfirmDialog by remember { mutableStateOf(false) }
    var fileToEmbedUri by remember { mutableStateOf<Uri?>(null) }

    val inspections = remember { com.pdfchemy.app.logic.LatestRequest() }
    val workerAccess = remember { kotlinx.coroutines.sync.Mutex() }
    var inspectionVersion by remember { mutableStateOf(0L) }
    var loadFailed by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var pendingExtraction by remember { mutableStateOf<AttachmentAction?>(null) }
    var pendingRemoval by remember { mutableStateOf<AttachmentAction?>(null) }
    var pendingEmbedding by remember { mutableStateOf<AttachmentAction?>(null) }
    var embeddingSource by remember { mutableStateOf<AttachmentAction?>(null) }

    fun current(request: AttachmentAction?): Boolean = request != null &&
        selectedPdfUri == request.source && inspections.isCurrent(request.generation)
    fun selectDocument(uri: Uri) {
        inspectionVersion = inspections.begin()
        selectedPdfUri = uri
        attachments = emptyList()
        targetRemoveAttachment = null; showRemoveConfirmDialog = false
        fileToEmbedUri = null; embeddingSource = null
        pendingExtraction = null; pendingRemoval = null; pendingEmbedding = null
        loadFailed = false; isLoading = true
    }
    fun refreshAttachments() { selectedPdfUri?.let(::selectDocument) }
    LaunchedEffect(effectiveInitialUri) {
        effectiveInitialUri?.takeIf { it != selectedPdfUri }?.let(::selectDocument)
    }
    LaunchedEffect(selectedPdfUri, inspectionVersion) {
        val uri = selectedPdfUri ?: return@LaunchedEffect
        val ticket = inspectionVersion
        withContext(Dispatchers.Main.immediate) {
            isLoading = true
            try {
                val contract = workerAccess.withLock {
                    PdfGateway.executeEngineTyped<AttachmentListContract>(context, "ATTACHMENT_LIST", uri, null, "{}")
                }
                if (inspections.isCurrent(ticket) && selectedPdfUri == uri) attachments = contract.attachments.map {
                    PdfAttachment(attachmentId = it.attachmentId, name = it.name, sizeBytes = it.sizeBytes, mimeType = it.mimeType)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (inspections.isCurrent(ticket) && selectedPdfUri == uri) loadFailed = true
            } finally {
                if (inspections.isCurrent(ticket) && selectedPdfUri == uri) isLoading = false
            }
        }
    }
    DisposableEffect(inspections) { onDispose { inspections.invalidate() } }
    val filePickerLauncher = rememberVanguardPdfPicker { uri -> selectDocument(uri) }
    fun executeOutput(destination: Uri?, request: AttachmentAction?, operation: String,
                      successTitle: Int, successDescription: Int, errorTitle: Int, selectOutput: Boolean = false) {
        if (destination == null) return
        if (!current(request)) {
            coroutineScope.launch(Dispatchers.IO) { com.pdfchemy.app.logic.OutputPolicy.deleteIfFresh(context, destination) }
            return
        }
        val snapshot = requireNotNull(request)
        isProcessing = true
        coroutineScope.launch(Dispatchers.Main.immediate) {
            try {
                workerAccess.withLock {
                    val params = JSONObject()
                    if (operation == "ATTACHMENT_EMBED") {
                        val extra = requireNotNull(snapshot.extra)
                        withContext(Dispatchers.IO) {
                            params.put("fileName", FileUtils.getFileName(context, extra)).put("fileSize", FileUtils.getFileSize(context, extra))
                        }
                        PdfGateway.executeEngineExtra(context, operation, snapshot.source, destination, extra, params.toString())
                    } else {
                        params.put("attachmentId", requireNotNull(snapshot.attachmentId))
                        PdfGateway.executeEngine(context, operation, snapshot.source, destination, params.toString())
                    }
                }
                if (current(snapshot)) {
                    viewModel.notifySuccess(context.getString(successTitle), context.getString(successDescription), destination)
                    if (selectOutput) selectDocument(destination)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current(snapshot)) viewModel.showErrorToast(context.getString(errorTitle), error.localizedMessage ?: "")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    com.pdfchemy.app.logic.OutputPolicy.deleteIfFresh(context, destination)
                }
                isProcessing = false
            }
        }
    }
    val pickEmbedFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = embeddingSource
        embeddingSource = null
        if (uri != null && current(request)) {
            pendingEmbedding = requireNotNull(request).copy(extra = uri)
            fileToEmbedUri = uri
        }
    }
    val extractSaveLauncher = rememberPreferredDocumentCreator("*/*") { destination ->
        val request = pendingExtraction; pendingExtraction = null
        executeOutput(destination, request, "ATTACHMENT_EXTRACT", R.string.title_extract_success,
            R.string.desc_extract_success, R.string.error_extract_failed)
    }
    val removeAttachmentSaveLauncher = rememberPreferredDocumentCreator("application/pdf") { destination ->
        val request = pendingRemoval; pendingRemoval = null
        executeOutput(destination, request, "ATTACHMENT_REMOVE", R.string.title_remove_attachment_success,
            R.string.desc_remove_attachment_success, R.string.error_remove_attachment_failed, selectOutput = true)
    }
    val saveAttachedPdfLauncher = rememberPreferredDocumentCreator("application/pdf") { destination ->
        val request = pendingEmbedding; pendingEmbedding = null; fileToEmbedUri = null
        executeOutput(destination, request, "ATTACHMENT_EMBED", R.string.title_embed_success,
            R.string.desc_embed_success, R.string.error_embed_failed, selectOutput = true)
    }
    LaunchedEffect(fileToEmbedUri) {
        val request = pendingEmbedding
        if (fileToEmbedUri != null && current(request)) {
            saveAttachedPdfLauncher.launch(com.pdfchemy.app.logic.FileUtil.generateSuggestedName(context, requireNotNull(request).source, "with_attachment"))
        }
    }
    val hasPending = pendingExtraction != null || pendingRemoval != null || pendingEmbedding != null || embeddingSource != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_attachment_manager), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    if (selectedPdfUri != null) {
                        IconButton(onClick = { pickEmbedFileLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.btn_embed_file))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.attachment_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.attachment_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // File selection
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isProcessing && !hasPending) { filePickerLauncher.launch(arrayOf("application/pdf")) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_pdf_for_attachments),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (selectedPdfUri != null) stringResource(R.string.tap_to_change_file) else stringResource(R.string.tap_to_browse),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            // Attachments List
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (loadFailed) {
                    Text(stringResource(R.string.error_open_file_failed), color = MaterialTheme.colorScheme.error)
                } else if (attachments.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (selectedPdfUri != null) stringResource(R.string.no_attachments_found_hint)
                            else stringResource(R.string.select_pdf_to_start),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(attachments, key = { _, item -> item.attachmentId }) { idx, item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            text = "${FileUtils.formatFileSize(item.sizeBytes)} Ã¢â‚¬Â¢ ${item.mimeType}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        enabled = !isProcessing && !hasPending,
                                        onClick = {
                                            pendingExtraction = selectedPdfUri?.let { AttachmentAction(it, inspectionVersion, item.attachmentId) }
                                            extractSaveLauncher.launch(item.name)
                                        }
                                    ) {
                                        Icon(Icons.Rounded.FileDownload, contentDescription = stringResource(R.string.desc_extract), tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(
                                        enabled = !isProcessing && !hasPending,
                                        onClick = {
                                            targetRemoveAttachment = item
                                            showRemoveConfirmDialog = true
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.btn_remove_attachment),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Embed New Attachment Button
            Button(
                onClick = {
                    embeddingSource = selectedPdfUri?.let { AttachmentAction(it, inspectionVersion) }
                    pickEmbedFileLauncher.launch(arrayOf("*/*"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                enabled = selectedPdfUri != null && !isLoading && !loadFailed && !isProcessing && !hasPending
            ) {
                Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.btn_embed_new_file),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showRemoveConfirmDialog && targetRemoveAttachment != null) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmDialog = false },
            title = { Text(stringResource(R.string.dialog_remove_attachment_title)) },
            text = { Text(stringResource(R.string.dialog_remove_attachment_msg, targetRemoveAttachment!!.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirmDialog = false
                        val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                        val attachSafe = targetRemoveAttachment!!.name.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                        pendingRemoval = selectedPdfUri?.let { AttachmentAction(it, inspectionVersion, targetRemoveAttachment!!.attachmentId) }
                        removeAttachmentSaveLauncher.launch("${base}_without_${attachSafe}.pdf")
                    }
                ) {
                    Text(stringResource(R.string.btn_remove_attachment), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
