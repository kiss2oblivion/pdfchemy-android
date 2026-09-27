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

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
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

    fun loadMetadata(uri: Uri) {
        coroutineScope.launch {
            isLoading = true
            val result = PdfMetadataEngine.readMetadata(context, uri)
            isLoading = false
            if (result.isSuccess) {
                val info = result.getOrThrow()
                metadataInfo = info
                title = info.title
                author = info.author
                subject = info.subject
                keywords = info.keywords
                creator = info.creator
                producer = info.producer
            } else {
                viewModel.showErrorToast(
                    context.getString(R.string.error_read_metadata_failed),
                    result.exceptionOrNull()?.localizedMessage ?: ""
                )
            }
        }
    }

    LaunchedEffect(initialPdfUri) {
        initialPdfUri?.let {
            selectedPdfUri = it
            loadMetadata(it)
        }
    }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        loadMetadata(uri)
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            coroutineScope.launch {
                isProcessing = true
                val newInfo = if (isWipeAction) null else DocumentMetadataInfo(
                    title = title,
                    author = author,
                    subject = subject,
                    keywords = keywords,
                    creator = creator,
                    producer = producer
                )
                val result = PdfMetadataEngine.writeOrSanitizeMetadata(
                    context = context,
                    sourcePdfUri = selectedPdfUri!!,
                    destPdfUri = destUri,
                    newMetadata = newInfo,
                    wipeAllMetadata = isWipeAction
                )
                isProcessing = false
                if (result.isSuccess) {
                    val titleRes = if (isWipeAction) R.string.title_sanitize_success else R.string.title_update_metadata_success
                    val descRes = if (isWipeAction) R.string.desc_sanitize_success else R.string.desc_update_metadata_success
                    viewModel.showSuccessToast(context.getString(titleRes), context.getString(descRes))
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_sanitize_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
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
                    .clickable { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
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
                                        "Clean document — zero personal tracking metadata detected.",
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
                                    label = { Text("Anonymize", fontSize = 11.sp) },
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
                                    label = { Text("Clear All", fontSize = 11.sp) },
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
                                    label = { Text("Reset", fontSize = 11.sp) },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                            OutlinedTextField(
                                value = title,
                                onValueChange = { title = it },
                                label = { Text(stringResource(R.string.meta_title)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = author,
                                onValueChange = { author = it },
                                label = { Text(stringResource(R.string.meta_author)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = subject,
                                onValueChange = { subject = it },
                                label = { Text(stringResource(R.string.meta_subject)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = keywords,
                                onValueChange = { keywords = it },
                                label = { Text(stringResource(R.string.meta_keywords)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = creator,
                                onValueChange = { creator = it },
                                label = { Text(stringResource(R.string.meta_creator)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }

                // Action Save Button
                Button(
                    onClick = {
                        val baseName = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: "document.pdf"
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
                    enabled = !isProcessing
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
