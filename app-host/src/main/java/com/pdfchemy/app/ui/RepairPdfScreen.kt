package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PdfRepairEngine
import com.pdfchemy.app.logic.RepairDiagnostic
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepairPdfScreen(
    viewModel: MainViewModel,
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var diagnostic by remember { mutableStateOf<RepairDiagnostic?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPdfUri) {
        val uri = selectedPdfUri
        if (uri != null) {
            val result = PdfRepairEngine.diagnosePdf(context, uri)
            if (result.isSuccess) {
                diagnostic = result.getOrThrow()
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedPdfUri = uri
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            coroutineScope.launch {
                isProcessing = true
                val result = PdfRepairEngine.repairPdf(
                    context = context,
                    sourcePdfUri = selectedPdfUri!!,
                    destPdfUri = destUri
                )
                isProcessing = false

                if (result.isSuccess) {
                    val count = result.getOrThrow()
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_repair_success),
                        context.getString(R.string.desc_repair_success, count)
                    )
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_repair_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_repair_pdf), fontWeight = FontWeight.Bold) },
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
            // Explanatory Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Build,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.repair_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.repair_subtitle),
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
                    .clickable { filePickerLauncher.launch(arrayOf("application/pdf", "application/octet-stream", "*/*")) },
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
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_corrupted_pdf),
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

            // Diagnostic Results
            if (diagnostic != null) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.label_diagnostic_report), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.label_pdf_header), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (diagnostic?.hasValidHeader == true) "Valid (%PDF-)" else "Corrupted / Missing",
                                fontWeight = FontWeight.Bold,
                                color = if (diagnostic?.hasValidHeader == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.label_eof_marker), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (diagnostic?.hasValidEof == true) "Valid (%%EOF)" else "Truncated / Missing",
                                fontWeight = FontWeight.Bold,
                                color = if (diagnostic?.hasValidEof == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.label_recovered_pages), style = MaterialTheme.typography.bodyMedium)
                            Text("${diagnostic?.recoveredPages ?: 0}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }

                        if (!diagnostic?.issueSummary.isNullOrBlank()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = "Issues: ${diagnostic?.issueSummary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Repair & Save Action Button
            Button(
                onClick = {
                    val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                    saveFileLauncher.launch("${base}_repaired.pdf")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                enabled = !isProcessing && selectedPdfUri != null
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_repair_and_rebuild),
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
