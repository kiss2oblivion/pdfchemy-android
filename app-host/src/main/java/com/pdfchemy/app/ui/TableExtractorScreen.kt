package com.pdfchemy.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.CsvParser
import com.pdfchemy.app.logic.FileUtil
import com.pdfchemy.app.logic.PdfTableExtractorEngine
import com.pdfchemy.app.logic.ShareUtil
import com.pdfchemy.app.logic.TableExtractionResult
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableExtractorScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val continuityUri by viewModel.continuityDocumentUri.collectAsState()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri ?: continuityUri) }
    var extractedCsv by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var isParsingCsv by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var isSharing by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var exportSourceUri by remember { mutableStateOf<Uri?>(null) }
    var exportSafeMode by remember { mutableStateOf(true) }
    var isSpreadsheetSafeCsv by remember { mutableStateOf(true) }
    var extractionError by remember { mutableStateOf<String?>(null) }
    var isEmptyResult by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableStateOf(0) }
    val extractionRequests = remember { com.pdfchemy.app.logic.LatestRequest() }

    var parsedRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }

    LaunchedEffect(extractedCsv) {
        if (extractedCsv.isEmpty()) {
            parsedRows = emptyList()
        } else {
            isParsingCsv = true
            val parseResult = withContext(Dispatchers.Default) {
                runCatching { CsvParser.parse(extractedCsv) }
            }
            parseResult.onSuccess { rows ->
                parsedRows = rows
                if (rows.isEmpty() && extractedCsv.isNotBlank()) {
                    isEmptyResult = true
                }
            }.onFailure { err ->
                parsedRows = emptyList()
                extractionError = err.message ?: context.getString(R.string.table_extractor_error_extract)
            }
            isParsingCsv = false
        }
    }

    // Authoritative extraction effect: responds to document selection, mode toggle, or retry
    LaunchedEffect(selectedPdfUri, isSpreadsheetSafeCsv, retryTrigger) {
        val uri = selectedPdfUri ?: return@LaunchedEffect
        val ticket = extractionRequests.begin()
        isExtracting = true
        extractionError = null
        isEmptyResult = false
        extractedCsv = ""
        shareError = null
        try {
            val result = PdfTableExtractorEngine.extractTables(context, uri, safeMode = isSpreadsheetSafeCsv)
            if (extractionRequests.isCurrent(ticket)) {
                when (result) {
                    is TableExtractionResult.Success -> {
                        extractedCsv = result.csv
                        isEmptyResult = false
                        extractionError = null
                    }
                    is TableExtractionResult.Empty -> {
                        extractedCsv = ""
                        isEmptyResult = true
                        extractionError = null
                    }
                    is TableExtractionResult.Failure -> {
                        extractedCsv = ""
                        isEmptyResult = false
                        extractionError = result.cause.message ?: context.getString(R.string.table_extractor_error_extract)
                        Toast.makeText(context, context.getString(R.string.table_extractor_error_extract), Toast.LENGTH_SHORT).show()
                    }
                    is TableExtractionResult.Cancelled -> {
                        // Cancelled, do not mutate state
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e("Table extraction failed", e)
            if (extractionRequests.isCurrent(ticket)) {
                extractionError = e.message ?: context.getString(R.string.table_extractor_error_extract)
                extractedCsv = ""
                isEmptyResult = false
                Toast.makeText(context, context.getString(R.string.table_extractor_error_extract), Toast.LENGTH_SHORT).show()
            }
        } finally {
            if (extractionRequests.isCurrent(ticket)) {
                isExtracting = false
            }
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        // Setting selectedPdfUri cleanly triggers extraction without duplicate effect execution
        selectedPdfUri = uri
    }

    val saveCsvLauncher = rememberPreferredDocumentCreator("text/csv") { destUri ->
        val srcUri = exportSourceUri ?: selectedPdfUri
        val safeMode = exportSafeMode
        if (destUri != null && srcUri != null) {
            if (isExporting) return@rememberPreferredDocumentCreator
            isExporting = true
            scope.launch {
                try {
                    val result = PdfTableExtractorEngine.extractTablesToFile(context, srcUri, destUri, safeMode = safeMode)
                    when (result) {
                        is TableExtractionResult.Success -> {
                            Toast.makeText(context, context.getString(R.string.table_extractor_success), Toast.LENGTH_SHORT).show()
                        }
                        is TableExtractionResult.Empty -> {
                            Toast.makeText(context, context.getString(R.string.table_extractor_no_tables), Toast.LENGTH_SHORT).show()
                        }
                        is TableExtractionResult.Failure -> {
                            Toast.makeText(context, context.getString(R.string.table_extractor_error_save), Toast.LENGTH_SHORT).show()
                        }
                        is TableExtractionResult.Cancelled -> {}
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Export cancelled
                } catch (e: Exception) {
                    AppLogger.e("CSV export failed", e)
                    Toast.makeText(context, context.getString(R.string.table_extractor_error_save), Toast.LENGTH_SHORT).show()
                } finally {
                    isExporting = false
                    exportSourceUri = null
                }
            }
        } else {
            exportSourceUri = null
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.table_extractor_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    if (extractedCsv.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val csvSnapshot = extractedCsv
                                if (csvSnapshot.isBlank() || isSharing) return@IconButton
                                isSharing = true
                                shareError = null
                                scope.launch(Dispatchers.Main.immediate) {
                                    try {
                                        val shareUri = com.pdfchemy.app.logic.DocumentActions.createRetainedCsvCopyAsync(context, csvSnapshot)
                                        ShareUtil.shareFile(context, shareUri, "text/csv", context.getString(R.string.share))
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        AppLogger.e("Share CSV failed", e)
                                        val message = if (e is com.pdfchemy.app.logic.RetainedShareQuotaException)
                                            R.string.integrity_share_storage_full else R.string.table_extractor_error_share
                                        shareError = context.getString(message)
                                    } finally {
                                        isSharing = false
                                    }
                                }
                            },
                            enabled = !isSharing
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.table_extractor_share_desc))
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.TableChart, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.select_pdf))
            }

            shareError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            selectedPdfUri?.let { uri ->
                val fileName = FileUtils.getFileName(context, uri) ?: "Document.pdf"
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
                        Text(fileName, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Checkbox(
                        checked = isSpreadsheetSafeCsv,
                        onCheckedChange = {
                            isSpreadsheetSafeCsv = it
                        }
                    )
                    Text(
                        if (isSpreadsheetSafeCsv) stringResource(R.string.table_extractor_safe_mode) else stringResource(R.string.table_extractor_raw_mode),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    if (isSpreadsheetSafeCsv)
                        stringResource(R.string.table_extractor_safe_mode_desc)
                    else
                        stringResource(R.string.table_extractor_raw_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (isSpreadsheetSafeCsv) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.table_extractor_safe_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isExtracting || isParsingCsv) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (parsedRows.isNotEmpty()) {
                Text(
                    stringResource(R.string.table_extractor_preview) + " (${parsedRows.size} rows)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // Tabular Grid View
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            itemsIndexed(parsedRows, key = { idx, _ -> idx }) { _, row ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    row.forEach { cell ->
                                        Text(
                                            text = cell.ifBlank { "-" },
                                            modifier = Modifier
                                                .widthIn(min = 90.dp, max = 220.dp)
                                                .padding(horizontal = 6.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        if (isExporting) return@Button
                        exportSourceUri = selectedPdfUri
                        exportSafeMode = isSpreadsheetSafeCsv
                        val suggestedName = FileUtil.generateSuggestedName(context, selectedPdfUri, "tables", extension = "csv")
                        saveCsvLauncher.launch(suggestedName)
                    },
                    enabled = !isExporting && selectedPdfUri != null && parsedRows.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.table_extractor_action_export))
                }
            } else if (extractionError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.table_extractor_error_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = sanitizeErrorMessage(extractionError) ?: stringResource(R.string.table_extractor_error_fallback),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        selectedPdfUri?.let { _ ->
                            Button(
                                onClick = { retryTrigger++ },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.table_extractor_retry))
                            }
                        }
                    }
                }
            } else if (isEmptyResult || (selectedPdfUri != null && parsedRows.isEmpty() && extractedCsv.isBlank())) {
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Rounded.TableChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.table_extractor_no_tables),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.table_extractor_no_tables_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

internal fun sanitizeErrorMessage(rawMessage: String?): String? {
    if (rawMessage.isNullOrBlank()) return null
    var sanitized = rawMessage.trim()

    // Strip leading exception class prefixes (e.g. "java.io.IOException: ...", "IllegalArgumentException: ...")
    sanitized = sanitized.replace(Regex("^(?:[a-zA-Z_][a-zA-Z0-9_.]*\\.)?([a-zA-Z0-9_]+(?:Exception|Error)):\\s*"), "")

    // Scrub Content and File URIs first so slashes in URIs aren't partially matched as unix paths
    sanitized = sanitized.replace(Regex("content://[^\"'\\s\\n:]+"), "[uri]")
    sanitized = sanitized.replace(Regex("file://[^\"'\\s\\n:]+"), "[uri]")

    // Scrub Android / Unix filesystem paths (e.g. /data/user/0/..., /storage/emulated/0/..., /proc/...)
    sanitized = sanitized.replace(Regex("/(?:[a-zA-Z0-9._-]+/)+[a-zA-Z0-9._-]*"), "[path]")

    // Scrub Windows file paths (e.g. C:\Users\...)
    sanitized = sanitized.replace(Regex("[a-zA-Z]:\\\\[^\"'\\s\\n]+"), "[path]")

    // Scrub stack trace frames
    val lines = sanitized.lines().filterNot { line ->
        val trimmed = line.trim()
        trimmed.startsWith("at ") || trimmed.startsWith("\tat ") || trimmed.contains(".kt:") || trimmed.contains(".java:")
    }
    sanitized = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()

    if (sanitized.isBlank()) return null

    // Limit display length to prevent UI overflow or information leakage
    val maxLength = 180
    return if (sanitized.length > maxLength) {
        sanitized.take(maxLength - 3).trimEnd() + "..."
    } else {
        sanitized
    }
}
