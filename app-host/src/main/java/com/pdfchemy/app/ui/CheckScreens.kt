package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.Screen
import com.pdfchemy.app.ToolCard
import com.pdfchemy.app.logic.PdfMetadata
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import com.pdfchemy.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckCategoryScreen(onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.check)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.widthIn(max = 800.dp).fillMaxHeight().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            item {
                ToolCard(
                    title = stringResource(R.string.menu_protect_pdf),
                    subtitle = stringResource(R.string.menu_protect_pdf_desc),
                    icon = Icons.Default.Lock,
                    onClick = { onNavigate(Screen.ProtectPdf) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_unlock_pdf),
                    subtitle = stringResource(R.string.menu_unlock_pdf_desc),
                    icon = Icons.Default.LockOpen,
                    onClick = { onNavigate(Screen.UnlockPdf()) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_inspect_metadata),
                    subtitle = stringResource(R.string.menu_inspect_metadata_desc),
                    icon = Icons.Rounded.Edit,
                    onClick = { onNavigate(Screen.InspectMetadata) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_remove_metadata),
                    subtitle = stringResource(R.string.menu_remove_metadata_desc),
                    icon = Icons.Rounded.DeleteSweep,
                    onClick = { onNavigate(Screen.StripMetadata) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_text_cleaner),
                    subtitle = stringResource(R.string.menu_text_cleaner_desc),
                    icon = Icons.Rounded.Edit,
                    onClick = { onNavigate(Screen.TextCleaner) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_extract),
                    subtitle = stringResource(R.string.menu_extract_desc),
                    icon = Icons.Rounded.DocumentScanner,
                    onClick = { onNavigate(Screen.ExtractText) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_compare_pdfs),
                    subtitle = stringResource(R.string.menu_compare_pdfs_desc),
                    icon = Icons.Rounded.Difference,
                    onClick = { onNavigate(Screen.ComparePdf) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_metadata_sanitizer),
                    subtitle = stringResource(R.string.menu_metadata_sanitizer_desc),
                    icon = Icons.Rounded.CleaningServices,
                    onClick = { onNavigate(Screen.MetadataSanitizer) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_flatten_pdf),
                    subtitle = stringResource(R.string.menu_flatten_pdf_desc),
                    icon = Icons.Filled.Lock,
                    onClick = { onNavigate(Screen.FlattenPdf) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_repair_pdf),
                    subtitle = stringResource(R.string.menu_repair_pdf_desc),
                    icon = Icons.Filled.Build,
                    onClick = { onNavigate(Screen.RepairPdf) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_redaction_studio),
                    subtitle = stringResource(R.string.menu_redaction_studio_desc),
                    icon = Icons.Filled.Lock,
                    onClick = { onNavigate(Screen.Redaction) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_pdfa_validator),
                    subtitle = stringResource(R.string.menu_pdfa_validator_desc),
                    icon = Icons.Rounded.FactCheck,
                    onClick = { onNavigate(Screen.PdfAValidator) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_font_inspector),
                    subtitle = stringResource(R.string.menu_font_inspector_desc),
                    icon = Icons.Rounded.Edit,
                    onClick = { onNavigate(Screen.FontInspector) }
                )
            }
            item {
                ToolCard(
                    title = stringResource(R.string.menu_doc_sanitizer),
                    subtitle = stringResource(R.string.menu_doc_sanitizer_desc),
                    icon = Icons.Rounded.VerifiedUser,
                    onClick = { onNavigate(Screen.DocumentSanitizer) }
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectMetadataScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { 
        viewModel.resetMetadata()
        onBack() 
    }
    val context = LocalContext.current
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    
    val currentMetadata by viewModel.currentMetadata.collectAsState()
    val analysis by viewModel.pdfAnalysis.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()

    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf("") }
    var producer by remember { mutableStateOf("") }

    // Update local state when metadata is loaded
    LaunchedEffect(currentMetadata) {
        currentMetadata?.let {
            title = it.title
            author = it.author
            subject = it.subject
            keywords = it.keywords
            creator = it.creator
            producer = it.producer
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        sourceUri = uri
        viewModel.loadMetadata(context, uri)
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null) {
            val src = sourceUri ?: return@rememberLauncherForActivityResult
            val newMetadata = PdfMetadata(title, author, subject, keywords, creator, producer)
            viewModel.updateMetadata(context, src, destUri, newMetadata)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inspect_edit_metadata)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { 
                        viewModel.resetMetadata()
                        onBack() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (sourceUri == null) {
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.select_pdf),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (isAnalyzing) {
                CircularProgressIndicator()
            } else {
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.select_different_pdf),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (analysis != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.document_stats), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            val a = analysis
                            if (a != null) {
                                Text(stringResource(R.string.label_pages_count, a.pageCount))
                                Text(stringResource(R.string.label_images_count, a.imageCount))
                                Text(stringResource(R.string.label_signatures, if (a.hasSignatures) stringResource(R.string.value_yes) else stringResource(R.string.value_no)))
                                Text(stringResource(R.string.label_type, a.scenario.name))
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.metadata_fields), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text(stringResource(R.string.author)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text(stringResource(R.string.subject)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text(stringResource(R.string.keywords)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = creator, onValueChange = { creator = it }, label = { Text(stringResource(R.string.creator)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = producer, onValueChange = { producer = it }, label = { Text(stringResource(R.string.producer)) }, modifier = Modifier.fillMaxWidth())
                    }
                }

                Button(
                    onClick = { sourceUri?.let { createDocLauncher.launch(com.pdfchemy.app.logic.FileUtil.generateSuggestedName(it, "metadata_updated")) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.save_changes_to_new_pdf),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StripMetadataScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        sourceUri = uri
        fileName = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri) ?: "Document.pdf"
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null) {
            val src = sourceUri ?: return@rememberLauncherForActivityResult
            viewModel.clearMetadata(context, src, destUri)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remove_metadata)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (sourceUri == null) {
                Text(stringResource(R.string.select_a_pdf_to_strip_all_meta), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.select_pdf),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.selected_file), style = MaterialTheme.typography.labelMedium)
                        Text(fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                
                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Text(stringResource(R.string.select_different_pdf))
                }
                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { sourceUri?.let { viewModel.clearMetadataOverwrite(context, it) } },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.overwrite_original),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Button(
                        onClick = { sourceUri?.let { createDocLauncher.launch(com.pdfchemy.app.logic.FileUtil.generateSuggestedName(it, "stripped")) } },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.save_as_new),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractTextScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<androidx.documentfile.provider.DocumentFile?>(null) }
    
    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
    }

    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { destUri ->
            selectedFile?.uri?.let { sourceUri ->
                viewModel.extractTextFromPdf(context, sourceUri, destUri)
                onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extract_text_ocr)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedFile != null) {
                ExtendedFloatingActionButton(
                    onClick = { 
                        val file = selectedFile ?: return@ExtendedFloatingActionButton
                        saveFileLauncher.launch("${file.name?.substringBeforeLast(".")}_text.txt") 
                    },
                    icon = { Icon(Icons.Rounded.DocumentScanner, contentDescription = "Extract") },
                    text = { Text(stringResource(R.string.extract_save_as_txt)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.select_pdf))
            }

            val file = selectedFile
            if (file != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FactCheck, contentDescription = "PDF", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(file.name ?: stringResource(R.string.default_doc_name), fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = "If the document contains standard text, it will be extracted instantly. If it is a scanned document (images), we will use on-device OCR (Optical Character Recognition) to read the text. This may take a few moments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
