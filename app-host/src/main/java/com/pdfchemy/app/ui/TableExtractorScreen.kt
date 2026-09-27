package com.pdfchemy.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.FileUtil
import com.pdfchemy.app.logic.PdfTableExtractorEngine
import com.pdfchemy.app.logic.ShareUtil
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
    var extractedCsv by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var isSpreadsheetSafeCsv by remember { mutableStateOf(false) }

    fun parseCsvRows(csv: String): List<List<String>> {
        if (csv.isBlank()) return emptyList()
        return csv.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val tokens = mutableListOf<String>()
                var current = StringBuilder()
                var inQuotes = false
                for (ch in line) {
                    if (ch == '\"') {
                        inQuotes = !inQuotes
                    } else if (ch == ',' && !inQuotes) {
                        tokens.add(current.toString().trim())
                        current = StringBuilder()
                    } else {
                        current.append(ch)
                    }
                }
                tokens.add(current.toString().trim())
                tokens
            }.toList()
    }

    val parsedRows = remember(extractedCsv) { parseCsvRows(extractedCsv) }

    fun extractTables(uri: Uri) {
        selectedPdfUri = uri
        isExtracting = true
        extractedCsv = ""
        scope.launch {
            try {
                val csv = PdfTableExtractorEngine.extractTablesToCsv(context, uri, safeMode = isSpreadsheetSafeCsv)
                extractedCsv = csv
            } catch (e: Exception) {
                AppLogger.e("Table extraction failed", e)
                Toast.makeText(context, "Error extracting tables", Toast.LENGTH_SHORT).show()
            } finally {
                isExtracting = false
            }
        }
    }

    LaunchedEffect(initialPdfUri, isSpreadsheetSafeCsv) {
        val uri = initialPdfUri ?: selectedPdfUri
        if (uri != null) {
            extractTables(uri)
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        extractTables(uri)
    }

    val saveCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { destUri ->
        val srcUri = selectedPdfUri
        if (destUri != null && srcUri != null) {
            isExporting = true
            scope.launch {
                try {
                    val success = PdfTableExtractorEngine.extractTablesToCsvFile(context, srcUri, destUri, safeMode = isSpreadsheetSafeCsv)
                    if (success) {
                        Toast.makeText(context, context.getString(R.string.table_extractor_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to save CSV", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    AppLogger.e("CSV export failed", e)
                } finally {
                    isExporting = false
                }
            }
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
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
                                    val tempFile = File(exportsDir, "extracted_tables_${System.currentTimeMillis()}.csv")
                                    FileOutputStream(tempFile).use { it.write(extractedCsv.toByteArray(Charsets.UTF_8)) }
                                    val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        tempFile
                                    )
                                    ShareUtil.shareFile(context, contentUri, "text/csv")
                                } catch (e: Exception) {
                                    AppLogger.e("Share CSV failed", e)
                                }
                            }
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share CSV")
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
                    Text("Spreadsheet-Safe Export", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "CSV files can contain formulas that spreadsheet applications may execute when opened. PDFchemy can preserve extracted values exactly, or sanitize formula-like values for safer spreadsheet use. For structured spreadsheet output, use the native XLSX export.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (isSpreadsheetSafeCsv) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Spreadsheet-Safe Export enabled. Formula-like values may be prefixed to prevent spreadsheet applications from evaluating them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            if (isExtracting) {
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
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
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
                        val suggestedName = FileUtil.generateSuggestedName(selectedPdfUri, "tables", extension = "csv")
                        saveCsvLauncher.launch(suggestedName)
                    },
                    enabled = !isExporting,
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
            } else if (selectedPdfUri != null) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.table_extractor_no_tables),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}
