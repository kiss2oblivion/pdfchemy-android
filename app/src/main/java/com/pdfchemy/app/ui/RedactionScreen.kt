package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PdfRedactionEngine
import com.pdfchemy.app.logic.RedactionBox
import com.pdfchemy.app.logic.RedactPattern
import com.pdfchemy.app.logic.RedactionConfig
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactionScreen(
    viewModel: MainViewModel,
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var searchQuery by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }

    var isBlackout by remember { mutableStateOf(true) }
    var forensicSanitize by remember { mutableStateOf(true) }
    var overlayText by remember { mutableStateOf("[REDACTED]") }
    
    var smartPatterns by remember { mutableStateOf(setOf<RedactPattern>()) }

    var foundBoxes by remember { mutableStateOf<List<RedactionBox>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    fun performSearch(query: String, regex: Boolean) {
        val uri = selectedPdfUri ?: return
        if (query.isBlank()) return

        coroutineScope.launch {
            isSearching = true
            val result = com.pdfchemy.app.logic.PdfRedactionEngine.searchRedactionTargets(
                context = context,
                pdfUri = uri,
                query = query,
                isRegex = regex
            )
            isSearching = false
            if (result.isSuccess) {
                foundBoxes = result.getOrThrow()
            }
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        foundBoxes = emptyList()
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null && foundBoxes.isNotEmpty()) {
            coroutineScope.launch {
                isProcessing = true
                val config = RedactionConfig(
                    isBlackout = isBlackout,
                    defaultOverlayText = overlayText,
                    forensicSanitize = forensicSanitize
                )

                val result = com.pdfchemy.app.logic.PdfRedactionEngine.applyRedactions(
                    context = context,
                    sourceUri = selectedPdfUri!!,
                    destUri = destUri,
                    redactions = foundBoxes,
                    config = config
                )
                isProcessing = false

                if (result.isSuccess) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_redaction_success),
                        context.getString(R.string.desc_redaction_success, result.getOrThrow())
                    )
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_redaction_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    val smartRedactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null && smartPatterns.isNotEmpty()) {
            coroutineScope.launch {
                isProcessing = true
                val config = RedactionConfig(
                    isBlackout = isBlackout,
                    defaultOverlayText = overlayText,
                    forensicSanitize = forensicSanitize
                )
                val result = com.pdfchemy.app.logic.PdfRedactionEngine.smartRedact(
                    context = context,
                    pdfUri = selectedPdfUri!!,
                    destUri = destUri,
                    patterns = smartPatterns.toList(),
                    config = config
                )
                isProcessing = false
                if (result.isSuccess) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_redaction_success),
                        context.getString(R.string.desc_redaction_success, result.getOrThrow())
                    )
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_redaction_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_redaction_studio), fontWeight = FontWeight.Bold) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.redaction_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.redaction_subtitle),
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
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_pdf_to_redact),
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

            // Search query & Preset pattern chips
            if (selectedPdfUri != null) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text(stringResource(R.string.placeholder_search_redact)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { performSearch(searchQuery, isRegex) }) {
                                        if (isSearching) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                        } else {
                                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                                        }
                                    }
                                }
                            )
                        }

                        // Presets
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SuggestionChip(
                                onClick = {
                                    searchQuery = "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
                                    isRegex = true
                                    performSearch(searchQuery, true)
                                },
                                label = { Text(stringResource(R.string.chip_preset_emails)) }
                            )
                            SuggestionChip(
                                onClick = {
                                    searchQuery = "\\b\\d{3}-\\d{2}-\\d{4}\\b"
                                    isRegex = true
                                    performSearch(searchQuery, true)
                                },
                                label = { Text(stringResource(R.string.chip_preset_ssn)) }
                            )
                            SuggestionChip(
                                onClick = {
                                    searchQuery = "\\b(?:\\d[ -]*?){13,16}\\b"
                                    isRegex = true
                                    performSearch(searchQuery, true)
                                },
                                label = { Text(stringResource(R.string.chip_preset_credit_cards)) }
                            )
                            SuggestionChip(
                                onClick = {
                                    searchQuery = "\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b"
                                    isRegex = true
                                    performSearch(searchQuery, true)
                                },
                                label = { Text(stringResource(R.string.chip_preset_phone)) }
                            )
                        }
                    }
                }

                // Redaction Style Options
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isBlackout,
                        onClick = { isBlackout = true },
                        label = { Text(stringResource(R.string.opt_blackout)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = !isBlackout,
                        onClick = { isBlackout = false },
                        label = { Text(stringResource(R.string.opt_whiteout)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Forensic Vector Sanitization Toggle
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Forensic Vector Sanitization", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text("Rasterizes redacted pages to obliterate underlying text bytes completely.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = forensicSanitize,
                        onCheckedChange = { forensicSanitize = it }
                    )
                }

                // Smart PII Redaction
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Smart PII Redaction", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RedactPattern.values().forEach { pattern ->
                                FilterChip(
                                    selected = smartPatterns.contains(pattern),
                                    onClick = {
                                        if (smartPatterns.contains(pattern)) {
                                            smartPatterns -= pattern
                                        } else {
                                            smartPatterns += pattern
                                        }
                                    },
                                    label = { Text(pattern.label, fontSize = 11.sp) }
                                )
                            }
                        }
                        if (smartPatterns.isNotEmpty()) {
                            Button(
                                onClick = {
                                    val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                                    smartRedactLauncher.launch("${base}_smart_redacted.pdf")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isProcessing
                            ) {
                                if (isProcessing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Auto-Scrub Selected PII")
                                }
                            }
                        }
                    }
                }
            }

            // Results List
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (foundBoxes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (selectedPdfUri != null) stringResource(R.string.no_redaction_targets_found)
                            else stringResource(R.string.select_pdf_to_start),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(foundBoxes, key = { idx, box -> "${box.pageIndex}_${box.normalizedRect.left}_${box.normalizedRect.top}_$idx" }) { idx, box ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.HighlightOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = box.overlayLabel ?: "Target #${idx + 1}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(stringResource(R.string.label_target_coords, box.pageIndex + 1, (box.normalizedRect.left * 100).toInt(), (box.normalizedRect.top * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(
                                        onClick = {
                                            val mutable = foundBoxes.toMutableList()
                                            mutable.removeAt(idx)
                                            foundBoxes = mutable
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sanitize and Save Button
            Button(
                onClick = {
                    val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                    saveFileLauncher.launch("${base}_redacted.pdf")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                enabled = !isProcessing && selectedPdfUri != null && foundBoxes.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onError)
                } else {
                    Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_apply_redactions, foundBoxes.size),
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
