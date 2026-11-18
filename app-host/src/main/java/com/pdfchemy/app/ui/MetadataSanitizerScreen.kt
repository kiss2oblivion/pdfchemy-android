package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.DocumentMetadataInfo
import com.pdfchemy.app.logic.PdfMetadataEngine
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

private data class MetadataSaveRequest(val source: Uri, val generation: Long,
    val wipe: Boolean, val metadata: DocumentMetadataInfo?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataSanitizerScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    val continuityUri by viewModel.continuityDocumentUri.collectAsState()
    val effectiveInitialPdfUri = initialPdfUri ?: continuityUri
    var selectedPdfUri by remember { mutableStateOf<Uri?>(effectiveInitialPdfUri) }
    var metadataInfo by remember { mutableStateOf<DocumentMetadataInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    // Editable fields
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf("") }
    var producer by remember { mutableStateOf("") }

    var isWipeAction by remember { mutableStateOf(true) }

    val inspections = remember { com.pdfchemy.app.logic.LatestRequest() }
    val workerAccess = remember { kotlinx.coroutines.sync.Mutex() }
    var inspectionVersion by remember { mutableStateOf(0L) }
    var loadFailed by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<MetadataSaveRequest?>(null) }

    fun loadMetadata(uri: Uri) {
        inspectionVersion = inspections.begin()
        selectedPdfUri = uri
        metadataInfo = null
        title = ""; author = ""; subject = ""; keywords = ""; creator = ""; producer = ""
        pendingSave = null
        loadFailed = false
        isLoading = true
    }
    LaunchedEffect(effectiveInitialPdfUri) {
        effectiveInitialPdfUri?.takeIf { it != selectedPdfUri }?.let(::loadMetadata)
    }
    LaunchedEffect(selectedPdfUri, inspectionVersion) {
        val uri = selectedPdfUri ?: return@LaunchedEffect
        val ticket = inspectionVersion
        withContext(Dispatchers.Main.immediate) {
            isLoading = true
            try {
                val info = workerAccess.withLock { PdfMetadataEngine.readMetadata(context, uri).getOrThrow() }
                if (inspections.isCurrent(ticket) && selectedPdfUri == uri) {
                    metadataInfo = info
                    title = info.title; author = info.author; subject = info.subject
                    keywords = info.keywords; creator = info.creator; producer = info.producer
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
    val pdfPickerLauncher = rememberVanguardPdfPicker { uri -> loadMetadata(uri) }

    val savePdfLauncher = rememberPreferredDocumentCreator("application/pdf") { destination ->
        val request = pendingSave
        pendingSave = null
        if (destination != null) {
            if (request == null || !inspections.isCurrent(request.generation) || selectedPdfUri != request.source) {
                coroutineScope.launch(Dispatchers.IO) { com.pdfchemy.app.logic.OutputPolicy.deleteIfFresh(context, destination) }
            } else {
                isProcessing = true
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    try {
                        workerAccess.withLock {
                            PdfMetadataEngine.writeOrSanitizeMetadata(context, request.source, destination,
                                request.metadata, request.wipe).getOrThrow()
                        }
                        if (inspections.isCurrent(request.generation)) {
                            val titleRes = if (request.wipe) R.string.title_sanitize_success else R.string.title_update_metadata_success
                            val descRes = if (request.wipe) R.string.desc_sanitize_success else R.string.desc_update_metadata_success
                            viewModel.showSuccessToast(context.getString(titleRes), context.getString(descRes), destination)
                            onBack()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (inspections.isCurrent(request.generation)) viewModel.showErrorToast(
                            context.getString(R.string.error_sanitize_failed), error.localizedMessage ?: "")
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                            com.pdfchemy.app.logic.OutputPolicy.deleteIfFresh(context, destination)
                        }
                        isProcessing = false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_metadata_sanitizer), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    if (metadataInfo != null) {
                        IconButton(
                            onClick = {
                                val info = metadataInfo ?: return@IconButton
                                val summaryText = buildString {
                                    appendLine("PDF Metadata Summary")
                                    appendLine("Title: ${info.title}")
                                    appendLine("Author: ${info.author}")
                                    appendLine("Subject: ${info.subject}")
                                    appendLine("Keywords: ${info.keywords}")
                                    appendLine("Creator: ${info.creator}")
                                    appendLine("Producer: ${info.producer}")
                                    appendLine("Created: ${info.creationDate}")
                                    appendLine("Modified: ${info.modificationDate}")
                                    appendLine("XMP Metadata: ${info.hasXmpMetadata}")
                                }.trim()
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("PDF Metadata", summaryText))
                                android.widget.Toast.makeText(context, context.getString(R.string.msg_metadata_summary_copied), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.desc_copy_metadata_summary))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.metadata_sanitizer_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.metadata_sanitizer_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // PDF Selection Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isProcessing && pendingSave == null) { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Rounded.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) }
                                ?: stringResource(R.string.select_pdf_for_metadata),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (selectedPdfUri != null) stringResource(R.string.tap_to_change_file)
                            else stringResource(R.string.tap_to_browse),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (loadFailed) {
                Text(stringResource(R.string.error_read_metadata_failed), color = MaterialTheme.colorScheme.error)
            } else if (metadataInfo != null) {
                // Metadata Overview Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.label_detected_metadata),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        MetadataRow(label = stringResource(R.string.meta_title), value = metadataInfo!!.title)
                        MetadataRow(label = stringResource(R.string.meta_author), value = metadataInfo!!.author)
                        MetadataRow(label = stringResource(R.string.meta_subject), value = metadataInfo!!.subject)
                        MetadataRow(label = stringResource(R.string.meta_keywords), value = metadataInfo!!.keywords)
                        MetadataRow(label = stringResource(R.string.meta_creator), value = metadataInfo!!.creator)
                        MetadataRow(label = stringResource(R.string.meta_producer), value = metadataInfo!!.producer)
                        MetadataRow(label = stringResource(R.string.meta_creation_date), value = metadataInfo!!.creationDate)
                        MetadataRow(label = stringResource(R.string.meta_mod_date), value = metadataInfo!!.modificationDate)

                        val isCompletelyClean = metadataInfo!!.title.isBlank() &&
                            metadataInfo!!.author.isBlank() &&
                            metadataInfo!!.subject.isBlank() &&
                            metadataInfo!!.keywords.isBlank() &&
                            metadataInfo!!.creator.isBlank() &&
                            metadataInfo!!.producer.isBlank() &&
                            !metadataInfo!!.hasXmpMetadata

                        if (isCompletelyClean) {
                            Surface(
                                color = Color(0xFF00E676).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF00C853))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        stringResource(R.string.metadata_clean_document),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF00C853),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        if (metadataInfo!!.hasXmpMetadata) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.meta_xmp_detected_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // Action Mode Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        enabled = !isProcessing && pendingSave == null,
                        selected = isWipeAction,
                        onClick = {
                            isWipeAction = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        label = { Text(stringResource(R.string.tab_wipe_sanitize)) },
                        leadingIcon = { Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        enabled = !isProcessing && pendingSave == null,
                        selected = !isWipeAction,
                        onClick = {
                            isWipeAction = false
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        label = { Text(stringResource(R.string.tab_edit_metadata)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isWipeAction) {
                    // Privacy Wipe Callout
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.wipe_privacy_notice_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.wipe_privacy_notice_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // Editable fields
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Quick Presets
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AssistChip(
                                    onClick = {
                                        author = "Anonymous"
                                        creator = ""
                                        producer = ""
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    label = { Text(stringResource(R.string.action_anonymize), fontSize = 11.sp) },
                                    leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                                AssistChip(
                                    onClick = {
                                        title = ""
                                        author = ""
                                        subject = ""
                                        keywords = ""
                                        creator = ""
                                        producer = ""
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    label = { Text(stringResource(R.string.clear_all), fontSize = 11.sp) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                                AssistChip(
                                    onClick = {
                                        metadataInfo?.let {
                                            title = it.title
                                            author = it.author
                                            subject = it.subject
                                            keywords = it.keywords
                                            creator = it.creator
                                            producer = it.producer
                                        }
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    label = { Text(stringResource(R.string.action_reset), fontSize = 11.sp) },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text(stringResource(R.string.meta_title)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isProcessing && pendingSave == null
                            )
                            OutlinedTextField(
                                value = author,
                                onValueChange = { author = it },
                                label = { Text(stringResource(R.string.meta_author)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isProcessing && pendingSave == null
                            )
                            OutlinedTextField(
                                value = subject,
                                onValueChange = { subject = it },
                                label = { Text(stringResource(R.string.meta_subject)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isProcessing && pendingSave == null
                            )
                            OutlinedTextField(
                                value = keywords,
                                onValueChange = { keywords = it },
                                label = { Text(stringResource(R.string.meta_keywords)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isProcessing && pendingSave == null
                            )
                            OutlinedTextField(
                                value = creator,
                                onValueChange = { creator = it },
                                label = { Text(stringResource(R.string.meta_creator)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                enabled = !isProcessing && pendingSave == null
                            )
                        }
                    }
                }

                // Action Save Button
                Button(
                    onClick = {
                        val source = selectedPdfUri ?: return@Button
                        pendingSave = MetadataSaveRequest(source, inspectionVersion, isWipeAction,
                            if (isWipeAction) null else DocumentMetadataInfo(title, author, subject, keywords, creator, producer))
                        val baseName = FileUtils.getFileName(context, source) ?: "document.pdf"
                        val suggestedName = if (isWipeAction) "sanitized_$baseName" else "updated_meta_$baseName"
                        savePdfLauncher.launch(suggestedName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = if (isWipeAction) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    },
                    enabled = !isProcessing && pendingSave == null
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        val btnIcon = if (isWipeAction) Icons.Rounded.CleaningServices else Icons.Rounded.Save
                        val btnText = if (isWipeAction) R.string.btn_wipe_and_save else R.string.btn_update_and_save
                        Icon(btnIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(btnText),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (value.isBlank()) stringResource(R.string.meta_empty) else value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Normal,
            color = if (value.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
