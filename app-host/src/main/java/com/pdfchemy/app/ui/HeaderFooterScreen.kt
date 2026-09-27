package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.pdfchemy.app.logic.BatesConfig
import com.pdfchemy.app.logic.HeaderFooterPosition
import com.pdfchemy.app.logic.PdfHeaderFooterEngine
import com.pdfchemy.app.logic.StampConfig
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeaderFooterScreen(
    viewModel: MainViewModel,
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var isBatesMode by remember { mutableStateOf(false) }

    var templateText by remember { mutableStateOf("Page {page} of {total}") }
    var selectedPosition by remember { mutableStateOf(HeaderFooterPosition.FOOTER_CENTER) }
    var fontSize by remember { mutableFloatStateOf(10f) }
    var marginPt by remember { mutableFloatStateOf(30f) }
    var startFromPage by remember { mutableIntStateOf(1) }

    // Bates config
    var batesPrefix by remember { mutableStateOf("CONFIDENTIAL-") }
    var batesSuffix by remember { mutableStateOf("") }
    var batesStartNumber by remember { mutableIntStateOf(1) }
    var batesDigits by remember { mutableIntStateOf(6) }

    var isProcessing by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            coroutineScope.launch {
                isProcessing = true
                val config = StampConfig(
                    templateText = templateText,
                    position = selectedPosition,
                    fontSize = fontSize,
                    marginPt = marginPt,
                    startFromPage = startFromPage,
                    batesConfig = BatesConfig(
                        enabled = isBatesMode,
                        prefix = batesPrefix,
                        suffix = batesSuffix,
                        startNumber = batesStartNumber,
                        digits = batesDigits
                    )
                )

                val result = PdfHeaderFooterEngine.applyHeaderFooter(
                    context = context,
                    sourcePdfUri = selectedPdfUri!!,
                    destPdfUri = destUri,
                    config = config
                )
                isProcessing = false

                if (result.isSuccess) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_stamp_success),
                        context.getString(R.string.desc_stamp_success)
                    )
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_stamp_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_header_footer_stamping), fontWeight = FontWeight.Bold) },
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.TextFields,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.header_footer_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.header_footer_subtitle),
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
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_pdf_to_stamp),
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

            // Stamping Mode FilterChips
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !isBatesMode,
                    onClick = { isBatesMode = false },
                    label = { Text(stringResource(R.string.tab_custom_header_footer)) },
                    leadingIcon = { Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = isBatesMode,
                    onClick = { isBatesMode = true },
                    label = { Text(stringResource(R.string.tab_legal_bates)) },
                    leadingIcon = { Icon(Icons.Rounded.Gavel, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Custom Header/Footer Inputs
            if (!isBatesMode) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.label_template_text), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = templateText,
                            onValueChange = { templateText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Text(stringResource(R.string.label_insert_variables), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(onClick = { templateText += " {page}" }) { Text("{page}") }
                            FilledTonalButton(onClick = { templateText += " {total}" }) { Text("{total}") }
                            FilledTonalButton(onClick = { templateText += " {date}" }) { Text("{date}") }
                            FilledTonalButton(onClick = { templateText += " {filename}" }) { Text("{filename}") }
                        }
                    }
                }
            } else {
                // Bates Numbering Inputs
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.label_bates_settings), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = batesPrefix,
                            onValueChange = { batesPrefix = it },
                            label = { Text(stringResource(R.string.label_bates_prefix)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = batesStartNumber.toString(),
                                onValueChange = { batesStartNumber = it.toIntOrNull() ?: 1 },
                                label = { Text(stringResource(R.string.label_bates_start_num)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = batesDigits.toString(),
                                onValueChange = { batesDigits = it.toIntOrNull()?.coerceIn(1, 10) ?: 6 },
                                label = { Text(stringResource(R.string.label_bates_digits)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // Position & Margins
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.label_stamp_position), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = selectedPosition == HeaderFooterPosition.HEADER_LEFT,
                            onClick = { selectedPosition = HeaderFooterPosition.HEADER_LEFT },
                            label = { Text("H-Left") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedPosition == HeaderFooterPosition.HEADER_CENTER,
                            onClick = { selectedPosition = HeaderFooterPosition.HEADER_CENTER },
                            label = { Text("H-Center") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedPosition == HeaderFooterPosition.HEADER_RIGHT,
                            onClick = { selectedPosition = HeaderFooterPosition.HEADER_RIGHT },
                            label = { Text("H-Right") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = selectedPosition == HeaderFooterPosition.FOOTER_LEFT,
                            onClick = { selectedPosition = HeaderFooterPosition.FOOTER_LEFT },
                            label = { Text("F-Left") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedPosition == HeaderFooterPosition.FOOTER_CENTER,
                            onClick = { selectedPosition = HeaderFooterPosition.FOOTER_CENTER },
                            label = { Text("F-Center") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedPosition == HeaderFooterPosition.FOOTER_RIGHT,
                            onClick = { selectedPosition = HeaderFooterPosition.FOOTER_RIGHT },
                            label = { Text("F-Right") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.label_font_size), style = MaterialTheme.typography.bodyMedium)
                        Text("${fontSize.toInt()} pt", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 8f..24f)
                }
            }

            // Apply and Save Button
            Button(
                onClick = {
                    val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                    val suffix = if (isBatesMode) "bates" else "stamped"
                    saveFileLauncher.launch("${base}_$suffix.pdf")
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
                    Icon(Icons.Rounded.TextFields, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_apply_stamp),
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
