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
import com.pdfchemy.app.logic.FlattenDiagnostic
import com.pdfchemy.app.logic.PdfFlattenEngine
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlattenPdfScreen(
    viewModel: MainViewModel,
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var diagnostic by remember { mutableStateOf<FlattenDiagnostic?>(null) }
    var flattenForms by remember { mutableStateOf(true) }
    var flattenAnnotations by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPdfUri) {
        val uri = selectedPdfUri
        if (uri != null) {
            val result = PdfFlattenEngine.inspectFlattenElements(context, uri)
            if (result.isSuccess) {
                diagnostic = result.getOrThrow()
            }
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            coroutineScope.launch {
                isProcessing = true
                val result = PdfFlattenEngine.flattenPdf(
                    context = context,
                    sourcePdfUri = selectedPdfUri!!,
                    destPdfUri = destUri,
                    flattenForms = flattenForms,
                    flattenAnnotations = flattenAnnotations
                )
                isProcessing = false

                if (result.isSuccess) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_flatten_success),
                        context.getString(R.string.desc_flatten_success)
                    )
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_flatten_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_flatten_pdf), fontWeight = FontWeight.Bold) },
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.flatten_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.flatten_subtitle),
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
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_pdf_to_flatten),
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

            // Diagnostic inspection
            if (diagnostic != null) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.label_detected_elements), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.label_form_fields), style = MaterialTheme.typography.bodyMedium)
                            Text("${diagnostic?.fieldCount ?: 0}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.label_annotations_stamps), style = MaterialTheme.typography.bodyMedium)
                            Text("${diagnostic?.annotationCount ?: 0}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.label_digital_signatures), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (diagnostic?.hasSignatures == true) stringResource(R.string.val_detected) else stringResource(R.string.val_none),
                                fontWeight = FontWeight.Bold,
                                color = if (diagnostic?.hasSignatures == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Options toggles
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.label_flatten_options), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.opt_flatten_forms), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.desc_opt_flatten_forms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = flattenForms, onCheckedChange = { flattenForms = it })
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.opt_flatten_annots), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.desc_opt_flatten_annots), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = flattenAnnotations, onCheckedChange = { flattenAnnotations = it })
                    }
                }
            }

            // Action button
            Button(
                onClick = {
                    val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                    saveFileLauncher.launch("${base}_flattened.pdf")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                enabled = !isProcessing && selectedPdfUri != null && (flattenForms || flattenAnnotations)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_flatten_pdf),
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
