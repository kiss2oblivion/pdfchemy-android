package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.utils.FileUtils

private fun getFileDisplaySize(context: Context, uri: Uri): String {
    return try {
        val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        FileUtils.formatFileSize(size)
    } catch (e: Exception) {
        ""
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrPdfScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var currentProgressPage by remember { mutableStateOf(0) }
    var totalProgressPages by remember { mutableStateOf(0) }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        selectedPdfUri = uri
    }

    val saveDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            isProcessing = true
            viewModel.createSearchablePdf(
                context = context,
                sourceUri = selectedPdfUri!!,
                destUri = destUri,
                onProgress = { current, total ->
                    currentProgressPage = current
                    totalProgressPages = total
                }
            ) { success ->
                isProcessing = false
                if (success) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_ocr_pdf)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.DocumentScanner,
                        contentDescription = null,
                        modifier = Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = stringResource(R.string.ocr_pdf_headline),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.ocr_pdf_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Pick Document Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isProcessing) { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Rounded.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (selectedPdfUri != null) {
                                    FileUtils.getFileName(context, selectedPdfUri!!) ?: stringResource(R.string.select_pdf_for_ocr)
                                } else {
                                    stringResource(R.string.select_pdf_for_ocr)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (selectedPdfUri != null) {
                                val sizeText = getFileDisplaySize(context, selectedPdfUri!!)
                                if (sizeText.isNotBlank()) {
                                    Text(
                                        text = sizeText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            enabled = !isProcessing
                        ) {
                            Text(if (selectedPdfUri == null) "Select" else "Change")
                        }
                    }
                }

                if (isProcessing) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = if (totalProgressPages > 0) {
                                    stringResource(R.string.ocr_processing_progress, currentProgressPage, totalProgressPages)
                                } else {
                                    stringResource(R.string.ocr_processing_generic)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else if (selectedPdfUri != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("100% Offline Text Layer", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "Scanned documents and pictures of text will be indexed with an invisible, high-precision text layer. You can select, copy, and search words anywhere inside the new PDF.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val originalName = FileUtils.getFileName(context, selectedPdfUri!!) ?: "Document.pdf"
                            val defaultName = "Searchable_${originalName}"
                            saveDocLauncher.launch(defaultName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.DocumentScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_create_searchable_pdf),
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
