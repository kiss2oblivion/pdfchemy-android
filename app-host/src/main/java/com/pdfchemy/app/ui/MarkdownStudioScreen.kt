package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.MarkdownEngine
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

enum class MarkdownViewMode {
    EDITOR, PREVIEW, SPLIT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownStudioScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var markdownText by remember {
        mutableStateOf(
            "# Welcome to Markdown Studio 📝\n\n" +
            "Create, edit, and convert markdown notes into clean vector PDF documents offline.\n\n" +
            "## Key Features\n" +
            "- **Live Syntax Toolbar**: Quick formatting for headings, bold, code, and tables.\n" +
            "- **100% Offline & Private**: Zero cloud uploads.\n" +
            "- **Direct PDF Export**: High-resolution vector typography.\n\n" +
            "### Code Snippet\n" +
            "```kotlin\n" +
            "val engine = MarkdownEngine()\n" +
            "engine.exportToPdf(doc)\n" +
            "```\n\n" +
            "> \"Simplicity is the soul of efficiency.\"\n"
        )
    }

    var viewMode by remember { mutableStateOf(MarkdownViewMode.EDITOR) }
    var documentTitle by remember { mutableStateOf("My Document") }
    var isProcessing by remember { mutableStateOf(false) }

    // Open Markdown / Text file
    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val text = stream.bufferedReader().readText()
                        withContext(Dispatchers.Main) {
                            markdownText = text
                            documentTitle = FileUtils.getFileName(context, uri)?.removeSuffix(".md") ?: "Document"
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e("Failed to open markdown file", e)
                }
            }
        }
    }

    // Import from PDF
    val importPdfLauncher = rememberVanguardPdfPicker { uri ->
        coroutineScope.launch {
            isProcessing = true
            val result = MarkdownEngine.pdfToMarkdown(context, uri)
            isProcessing = false
            if (result.isSuccess) {
                markdownText = result.getOrThrow()
                documentTitle = FileUtils.getFileName(context, uri)?.removeSuffix(".pdf") ?: "Extracted Doc"
                viewModel.showSuccessToast(
                    context.getString(R.string.title_pdf_to_md_success),
                    context.getString(R.string.desc_pdf_to_md_success)
                )
            } else {
                viewModel.showErrorToast(
                    context.getString(R.string.error_pdf_to_md_failed),
                    result.exceptionOrNull()?.localizedMessage ?: ""
                )
            }
        }
    }

    // Save as PDF
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null) {
            coroutineScope.launch {
                isProcessing = true
                val result = MarkdownEngine.markdownToPdf(
                    context = context,
                    markdownText = markdownText,
                    destPdfUri = destUri,
                    documentTitle = documentTitle
                )
                isProcessing = false
                if (result.isSuccess) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_md_to_pdf_success),
                        context.getString(R.string.desc_md_to_pdf_success)
                    )
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_md_to_pdf_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    // Save as .MD file
    val saveMdLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { destUri ->
        if (destUri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        out.write(markdownText.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.showSuccessToast(
                            context.getString(R.string.title_save_md_success),
                            context.getString(R.string.desc_save_md_success)
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        viewModel.showErrorToast(context.getString(R.string.error_save_md_failed), e.localizedMessage ?: "")
                    }
                }
            }
        }
    }

    fun insertText(token: String) {
        markdownText = if (markdownText.endsWith("\n") || markdownText.isEmpty()) {
            markdownText + token
        } else {
            markdownText + "\n" + token
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_markdown_studio), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = { openFileLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.btn_open_file))
                    }
                    IconButton(onClick = { importPdfLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = stringResource(R.string.btn_import_pdf_to_md), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        val fileName = if (documentTitle.isBlank()) "document.md" else "$documentTitle.md"
                        saveMdLauncher.launch(fileName)
                    }) {
                        Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.btn_save_md))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Mode selector tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = viewMode == MarkdownViewMode.EDITOR,
                    onClick = { viewMode = MarkdownViewMode.EDITOR },
                    label = { Text(stringResource(R.string.tab_md_editor)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = viewMode == MarkdownViewMode.PREVIEW,
                    onClick = { viewMode = MarkdownViewMode.PREVIEW },
                    label = { Text(stringResource(R.string.tab_md_preview)) },
                    leadingIcon = { Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = viewMode == MarkdownViewMode.SPLIT,
                    onClick = { viewMode = MarkdownViewMode.SPLIT },
                    label = { Text(stringResource(R.string.tab_md_split)) },
                    leadingIcon = { Icon(Icons.Rounded.VerticalSplit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Quick Formatting Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarButton(text = "# H1") { insertText("# Heading 1") }
                ToolbarButton(text = "## H2") { insertText("## Heading 2") }
                ToolbarButton(text = "### H3") { insertText("### Heading 3") }
                ToolbarIconButton(icon = Icons.Rounded.FormatBold) { insertText("**Bold text**") }
                ToolbarIconButton(icon = Icons.Rounded.FormatItalic) { insertText("*Italic text*") }
                ToolbarIconButton(icon = Icons.Rounded.FormatQuote) { insertText("> Quote text") }
                ToolbarIconButton(icon = Icons.Rounded.Code) { insertText("```\n// Code snippet\n```") }
                ToolbarIconButton(icon = Icons.Rounded.FormatListBulleted) { insertText("- List item") }
                ToolbarIconButton(icon = Icons.Rounded.FormatListNumbered) { insertText("1. Numbered item") }
                ToolbarIconButton(icon = Icons.Rounded.Checklist) { insertText("- [ ] Task item") }
                ToolbarIconButton(icon = Icons.Rounded.TableChart) {
                    insertText("| Header 1 | Header 2 |\n| --- | --- |\n| Cell 1 | Cell 2 |")
                }
                ToolbarIconButton(icon = Icons.Rounded.HorizontalRule) { insertText("\n---\n") }
            }

            // Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                when (viewMode) {
                    MarkdownViewMode.EDITOR -> {
                        OutlinedTextField(
                            value = markdownText,
                            onValueChange = { markdownText = it },
                            modifier = Modifier.fillMaxSize(),
                            placeholder = { Text(stringResource(R.string.hint_type_markdown)) },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                        )
                    }
                    MarkdownViewMode.PREVIEW -> {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            MarkdownPreviewRenderer(
                                markdown = markdownText,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                            )
                        }
                    }
                    MarkdownViewMode.SPLIT -> {
                        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = markdownText,
                                onValueChange = { markdownText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            )
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                MarkdownPreviewRenderer(
                                    markdown = markdownText,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Export Actions Bottom Bar
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = documentTitle,
                        onValueChange = { documentTitle = it },
                        label = { Text(stringResource(R.string.label_doc_title)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            val targetName = if (documentTitle.isBlank()) "document.pdf" else "$documentTitle.pdf"
                            savePdfLauncher.launch(targetName)
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 52.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        enabled = !isProcessing && markdownText.isNotBlank()
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.btn_export_pdf),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = Modifier.height(34.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun ToolbarIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MarkdownPreviewRenderer(
    markdown: String,
    modifier: Modifier = Modifier
) {
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val lines = markdown.lines()
            var inCode = false
            val codeBuf = mutableListOf<String>()

            for (line in lines) {
                val trimmed = line.trim()

                if (trimmed.startsWith("```")) {
                    if (inCode) {
                        inCode = false
                        // Render code block
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = codeBuf.joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        codeBuf.clear()
                    } else {
                        inCode = true
                        codeBuf.clear()
                    }
                    continue
                }

                if (inCode) {
                    codeBuf.add(line)
                    continue
                }

                when {
                    trimmed.startsWith("# ") -> {
                        Text(
                            text = trimmed.removePrefix("# ").trim(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    trimmed.startsWith("## ") -> {
                        Text(
                            text = trimmed.removePrefix("## ").trim(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    trimmed.startsWith("### ") -> {
                        Text(
                            text = trimmed.removePrefix("### ").trim(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    trimmed.startsWith(">") -> {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(modifier = Modifier.padding(10.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(20.dp)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = trimmed.removePrefix(">").trim(),
                                    fontStyle = FontStyle.Italic,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        Row(modifier = Modifier.padding(start = 8.dp)) {
                            Text("• ", fontWeight = FontWeight.Bold)
                            Text(trimmed.substring(2).trim(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    trimmed.matches(Regex("^-{3,}|_{3,}|\\*{3,}$")) -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    trimmed.isNotBlank() -> {
                        Text(text = trimmed, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
