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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.ComicBookEngine
import com.pdfchemy.app.logic.PdfToEpubEngine
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

enum class EbookMode {
    PDF_TO_EPUB,
    EPUB_TO_PDF,
    PDF_TO_CBZ,
    CBZ_TO_PDF
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookConverterScreen(
    viewModel: MainViewModel,
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedMode by remember { mutableStateOf(EbookMode.PDF_TO_EPUB) }
    var selectedSourceUri by remember { mutableStateOf<Uri?>(initialUri) }
    var bookTitle by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedSourceUri = uri
        val baseName = FileUtils.getFileName(context, uri)?.substringBeforeLast(".") ?: "My Book"
        bookTitle = baseName
    }

    // Save Output Launcher
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            when (selectedMode) {
                EbookMode.PDF_TO_EPUB -> "application/epub+zip"
                EbookMode.EPUB_TO_PDF -> "application/pdf"
                EbookMode.PDF_TO_CBZ -> "application/vnd.comicbook+zip"
                EbookMode.CBZ_TO_PDF -> "application/pdf"
            }
        )
    ) { destUri ->
        if (destUri != null && selectedSourceUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    destUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            coroutineScope.launch {
                isProcessing = true
                val result: Result<Boolean> = when (selectedMode) {
                    EbookMode.PDF_TO_EPUB -> {
                        com.pdfchemy.app.sandbox.SandboxCoordinator.pdfToEpub(
                            context = context,
                            sourcePdfUri = selectedSourceUri!!,
                            destEpubUri = destUri,
                            bookTitle = bookTitle.ifBlank { "Untitled E-Book" },
                            authorName = authorName.ifBlank { "Unknown Author" }
                        )
                    }
                    EbookMode.EPUB_TO_PDF -> {
                        com.pdfchemy.app.sandbox.SandboxCoordinator.epubToPdf(
                            context = context,
                            sourceEpubUri = selectedSourceUri!!,
                            destPdfUri = destUri,
                            onProgress = { c, t ->
                                progressCurrent = c
                                progressTotal = t
                            }
                        )
                    }
                    EbookMode.PDF_TO_CBZ -> {
                        ComicBookEngine.convertPdfToCbz(
                            context = context,
                            sourcePdfUri = selectedSourceUri!!,
                            destCbzUri = destUri
                        ).map { true }
                    }
                    EbookMode.CBZ_TO_PDF -> {
                        ComicBookEngine.convertCbzToPdf(
                            context = context,
                            sourceCbzUri = selectedSourceUri!!,
                            destPdfUri = destUri
                        ).map { true }
                    }
                }
                isProcessing = false

                if (result.isSuccess) {
                    viewModel.refreshHistory()
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_ebook_success),
                        context.getString(R.string.desc_ebook_success)
                    )
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_ebook_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_ebook_suite), fontWeight = FontWeight.Bold) },
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.ebook_suite_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.ebook_suite_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Mode Selector Chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.label_choose_ebook_format),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedMode == EbookMode.PDF_TO_EPUB,
                        onClick = { selectedMode = EbookMode.PDF_TO_EPUB; selectedSourceUri = null },
                        label = { Text(stringResource(R.string.tab_pdf_to_epub), maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.AutoStories, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedMode == EbookMode.EPUB_TO_PDF,
                        onClick = { selectedMode = EbookMode.EPUB_TO_PDF; selectedSourceUri = null },
                        label = { Text(stringResource(R.string.tab_epub_to_pdf), maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedMode == EbookMode.PDF_TO_CBZ,
                        onClick = { selectedMode = EbookMode.PDF_TO_CBZ; selectedSourceUri = null },
                        label = { Text(stringResource(R.string.tab_pdf_to_cbz), maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedMode == EbookMode.CBZ_TO_PDF,
                        onClick = { selectedMode = EbookMode.CBZ_TO_PDF; selectedSourceUri = null },
                        label = { Text(stringResource(R.string.tab_cbz_to_pdf), maxLines = 1) },
                        leadingIcon = { Icon(Icons.Rounded.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Source File Selection Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val mimeTypes = when (selectedMode) {
                            EbookMode.PDF_TO_EPUB, EbookMode.PDF_TO_CBZ -> arrayOf("application/pdf")
                            EbookMode.EPUB_TO_PDF -> arrayOf("application/epub+zip", "application/zip", "application/octet-stream")
                            EbookMode.CBZ_TO_PDF -> arrayOf("application/zip", "application/x-cbz", "application/octet-stream")
                        }
                        filePickerLauncher.launch(mimeTypes)
                    },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val icon = when (selectedMode) {
                        EbookMode.PDF_TO_EPUB, EbookMode.PDF_TO_CBZ -> Icons.Rounded.PictureAsPdf
                        EbookMode.EPUB_TO_PDF -> Icons.Rounded.AutoStories
                        EbookMode.CBZ_TO_PDF -> Icons.Rounded.FolderZip
                    }
                    val defaultTitle = when (selectedMode) {
                        EbookMode.PDF_TO_EPUB, EbookMode.PDF_TO_CBZ -> stringResource(R.string.select_pdf_for_ebook)
                        EbookMode.EPUB_TO_PDF -> stringResource(R.string.select_epub_for_pdf)
                        EbookMode.CBZ_TO_PDF -> stringResource(R.string.select_cbz_file)
                    }
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedSourceUri?.let { FileUtils.getFileName(context, it) } ?: defaultTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (selectedSourceUri != null) stringResource(R.string.tap_to_change_file)
                            else stringResource(R.string.tap_to_browse),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            // Metadata fields for EPUB
            if (selectedMode == EbookMode.PDF_TO_EPUB && selectedSourceUri != null) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.label_ebook_metadata), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = bookTitle,
                            onValueChange = { bookTitle = it },
                            label = { Text(stringResource(R.string.meta_title)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = authorName,
                            onValueChange = { authorName = it },
                            label = { Text(stringResource(R.string.meta_author)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // Conversion Progress Bar
            if (isProcessing && progressTotal > 0) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.label_generating_sheet, progressCurrent, progressTotal),
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = { progressCurrent.toFloat() / progressTotal.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Convert and Save Button
            Button(
                onClick = {
                    val base = selectedSourceUri?.let { FileUtils.getFileName(context, it)?.substringBeforeLast(".") } ?: "ebook"
                    val suggested = when (selectedMode) {
                        EbookMode.PDF_TO_EPUB -> "$base.epub"
                        EbookMode.EPUB_TO_PDF, EbookMode.CBZ_TO_PDF -> "$base.pdf"
                        EbookMode.PDF_TO_CBZ -> "$base.cbz"
                    }
                    saveFileLauncher.launch(suggested)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                enabled = !isProcessing && selectedSourceUri != null
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    val btnText = when (selectedMode) {
                        EbookMode.PDF_TO_EPUB -> R.string.btn_convert_to_epub
                        EbookMode.EPUB_TO_PDF, EbookMode.CBZ_TO_PDF -> R.string.btn_convert_to_pdf
                        EbookMode.PDF_TO_CBZ -> R.string.btn_convert_to_cbz
                    }
                    Icon(Icons.Rounded.AutoStories, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(btnText),
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
