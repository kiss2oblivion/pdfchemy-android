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
import org.json.JSONArray
import org.json.JSONObject
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

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

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var attachments by remember { mutableStateOf<List<PdfAttachment>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    var targetExtractAttachment by remember { mutableStateOf<PdfAttachment?>(null) }
    var fileToEmbedUri by remember { mutableStateOf<Uri?>(null) }

    fun refreshAttachments() {
        val uri = selectedPdfUri ?: return
        coroutineScope.launch {
            isLoading = true
            try {
                val jsonResult = PdfGateway.executeEngine(context, "ATTACHMENT_LIST", uri, null, "{}")
                val jsonArray = JSONArray(jsonResult)
                val newList = mutableListOf<PdfAttachment>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    newList.add(PdfAttachment(
                        name = item.optString("name"),
                        sizeBytes = item.optLong("sizeBytes"),
                        mimeType = item.optString("mimeType")
                    ))
                }
                attachments = newList
            } catch (e: Exception) {
                // handle error
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(selectedPdfUri) {
        if (selectedPdfUri != null) {
            refreshAttachments()
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
    }

    val pickEmbedFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            fileToEmbedUri = uri
        }
    }

    val extractSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null && targetExtractAttachment != null) {
            coroutineScope.launch {
                try {
                    val params = JSONObject().put("attachmentName", targetExtractAttachment!!.name)
                    PdfGateway.executeEngine(context, "ATTACHMENT_EXTRACT", selectedPdfUri!!, destUri, params.toString())
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_extract_success),
                        context.getString(R.string.desc_extract_success)
                    )
                } catch (e: Exception) {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_extract_failed),
                        e.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    val saveAttachedPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null && fileToEmbedUri != null) {
            coroutineScope.launch {
                try {
                    val params = JSONObject().put("fileName", FileUtils.getFileName(context, fileToEmbedUri!!)).put("fileSize", FileUtils.getFileSize(context, fileToEmbedUri!!))
                    PdfGateway.executeEngineExtra(context, "ATTACHMENT_EMBED", selectedPdfUri!!, destUri, fileToEmbedUri!!, params.toString())
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_embed_success),
                        context.getString(R.string.desc_embed_success)
                    )
                } catch (e: Exception) {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_embed_failed),
                        e.localizedMessage ?: ""
                    )
                }
                if (false) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_embed_success),
                        context.getString(R.string.desc_embed_success)
                    )
                    selectedPdfUri = destUri
                    fileToEmbedUri = null
                    refreshAttachments()
                }
            }
        }
    }

    LaunchedEffect(fileToEmbedUri) {
        val fileUri = fileToEmbedUri
        val pdfUri = selectedPdfUri
        if (fileUri != null && pdfUri != null) {
            val base = FileUtils.getFileName(context, pdfUri)?.removeSuffix(".pdf") ?: "document"
            saveAttachedPdfLauncher.launch("${base}_with_attachment.pdf")
        }
    }

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
                    .clickable { filePickerLauncher.launch(arrayOf("application/pdf")) },
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
                        itemsIndexed(attachments, key = { _, item -> "${item.name}_${item.sizeBytes}" }) { idx, item ->
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
                                    Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            text = "${FileUtils.formatFileSize(item.sizeBytes)} • ${item.mimeType}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            targetExtractAttachment = item
                                            extractSaveLauncher.launch(item.name)
                                        }
                                    ) {
                                        Icon(Icons.Rounded.FileDownload, contentDescription = "Extract", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Embed New Attachment Button
            Button(
                onClick = { pickEmbedFileLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                enabled = selectedPdfUri != null
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
}
