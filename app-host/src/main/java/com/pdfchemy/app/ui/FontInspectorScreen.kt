package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PdfFontInfo
import com.pdfchemy.app.logic.PdfFontInspectorEngine
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

private data class FontExportRequest(val source: Uri, val count: Int, val generation: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontInspectorScreen(
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
    var fontList by remember { mutableStateOf<List<PdfFontInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var exportSource by remember { mutableStateOf<FontExportRequest?>(null) }

    val inspections = remember { com.pdfchemy.app.logic.LatestRequest() }
    val workerAccess = remember { kotlinx.coroutines.sync.Mutex() }
    var inspectionVersion by remember { mutableStateOf(0L) }
    var loadFailed by remember { mutableStateOf(false) }

    fun selectDocument(uri: Uri) {
        inspectionVersion = inspections.begin()
        selectedPdfUri = uri
        fontList = emptyList()
        exportSource = null
        loadFailed = false
        isLoading = true
    }
    fun refreshFonts() { selectedPdfUri?.let(::selectDocument) }
    LaunchedEffect(effectiveInitialUri) {
        effectiveInitialUri?.takeIf { it != selectedPdfUri }?.let(::selectDocument)
    }
    LaunchedEffect(selectedPdfUri, inspectionVersion) {
        val uri = selectedPdfUri ?: return@LaunchedEffect
        val ticket = inspectionVersion
        withContext(Dispatchers.Main.immediate) {
            isLoading = true
            try {
                val result = workerAccess.withLock { PdfFontInspectorEngine.inspectFonts(context, uri).getOrThrow() }
                if (inspections.isCurrent(ticket) && selectedPdfUri == uri) fontList = result
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

    val saveZipLauncher = rememberPreferredDocumentDestinationCreator("application/zip") { destination ->
        val snapshot = exportSource
        exportSource = null
        if (destination != null) {
            isExporting = true
            coroutineScope.launch(Dispatchers.Main.immediate) {
                try {
                    val request = requireNotNull(snapshot) { "No source selected for export" }
                    if (!inspections.isCurrent(request.generation) || selectedPdfUri != request.source) return@launch
                    workerAccess.withLock { PdfFontInspectorEngine.extractEmbeddedFonts(context, request.source, destination.uri).getOrThrow() }
                    if (!inspections.isCurrent(request.generation)) return@launch
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.title_fonts_exported_success, request.count),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (snapshot != null && inspections.isCurrent(snapshot.generation)) android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.error_fonts_export_failed),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                        com.pdfchemy.app.logic.OutputPolicy.deleteIfFresh(context, destination.uri)
                    }
                    isExporting = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_font_inspector), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    if (fontList.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val reportText = buildString {
                                    appendLine("PDF Font Inspection Report (${fontList.size} fonts)")
                                    fontList.forEach { f ->
                                        appendLine("- ${f.familyName}: ${f.formatType} | ${if (f.isEmbedded) "Embedded" else "System"} | ${f.encoding} (${f.pageCountUsed}p)")
                                    }
                                }.trim()
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Font Report", reportText))
                                android.widget.Toast.makeText(context, context.getString(R.string.msg_font_report_copied), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.desc_copy_font_report))
                        }
                    }
                    if (selectedPdfUri != null) {
                        IconButton(enabled = !isExporting && exportSource == null, onClick = { refreshFonts() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.desc_refresh))
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.FontDownload,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.font_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.font_subtitle),
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
                    .clickable(enabled = !isExporting && exportSource == null) { filePickerLauncher.launch(arrayOf("application/pdf")) },
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
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_pdf_for_fonts),
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

            // Fonts List Area
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (loadFailed) {
                    Text(stringResource(R.string.error_open_file_failed), color = MaterialTheme.colorScheme.error)
                } else if (fontList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (selectedPdfUri != null) stringResource(R.string.no_fonts_detected)
                            else stringResource(R.string.select_pdf_to_start),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Summary Stats Card
                        val embeddedCount = fontList.count { it.isEmbedded }
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${fontList.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.stat_total_fonts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$embeddedCount", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(stringResource(R.string.stat_embedded), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${fontList.size - embeddedCount}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.stat_standard_system), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // Font items
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(fontList, key = { "${it.familyName}_${it.formatType}_${it.encoding}" }, contentType = { "font" }) { font ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.FontDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = font.familyName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                text = "${font.formatType} â€¢ ${stringResource(if (font.isEmbedded) R.string.stat_embedded else R.string.font_system_font)} â€¢ ${font.encoding}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text("${font.pageCountUsed}p") }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Export Embedded Fonts Action Button
            val embeddedCount = fontList.count { it.isEmbedded }
            if (embeddedCount > 0) {
                Button(
                    onClick = {
                        val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                        exportSource = selectedPdfUri?.let { FontExportRequest(it, embeddedCount, inspectionVersion) }
                        saveZipLauncher.launch("${base}_embedded_fonts.zip")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    enabled = !isLoading && !isExporting && exportSource == null
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(stringResource(R.string.btn_exporting_fonts))
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_export_embedded_fonts))
                    }
                }
            }
        }
    }
}
