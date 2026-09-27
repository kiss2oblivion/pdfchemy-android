package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var fontList by remember { mutableStateOf<List<PdfFontInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun refreshFonts() {
        val uri = selectedPdfUri ?: return
        coroutineScope.launch {
            isLoading = true
            val result = PdfFontInspectorEngine.inspectFonts(context, uri)
            isLoading = false
            if (result.isSuccess) {
                fontList = result.getOrThrow()
            }
        }
    }

    LaunchedEffect(selectedPdfUri) {
        if (selectedPdfUri != null) {
            refreshFonts()
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
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
                    if (selectedPdfUri != null) {
                        IconButton(onClick = { refreshFonts() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
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
                                                text = "${font.formatType} • ${if (font.isEmbedded) "Embedded" else "System Font"} • ${font.encoding}",
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
        }
    }
}
