package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.FindReplaceSummary
import com.pdfchemy.app.logic.PdfFindAndReplaceEngine
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindAndReplaceScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
    var findQuery by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }

    var searchSummary by remember { mutableStateOf<FindReplaceSummary?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        searchSummary = null
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null && findQuery.isNotBlank()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.replaceTextOccurrences(
                context = context,
                sourcePdfUri = selectedPdfUri!!,
                destPdfUri = destUri,
                findText = findQuery,
                replaceText = replaceText,
                matchCase = matchCase
            ) { success ->
                if (success) {
                    searchSummary = null
                }
            }
        }
    }

    fun performSearch() {
        if (selectedPdfUri != null && findQuery.isNotBlank()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                isSearching = true
                searchSummary = PdfFindAndReplaceEngine.findOccurrences(
                    context = context,
                    pdfUri = selectedPdfUri!!,
                    query = findQuery,
                    matchCase = matchCase
                )
                isSearching = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.menu_find_replace),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Rounded.FindInPage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(36.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.banner_find_replace_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = stringResource(R.string.banner_find_replace_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // PDF Picker Card
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Rounded.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.btn_choose_pdf_file),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (selectedPdfUri != null) {
                                    stringResource(R.string.tap_to_change_file)
                                } else {
                                    stringResource(R.string.tap_to_browse)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Inputs Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            searchSummary = null
                        },
                        label = { Text(stringResource(R.string.label_find_text)) },
                        placeholder = { Text(stringResource(R.string.hint_find_text)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (findQuery.isNotEmpty()) {
                                    IconButton(onClick = { findQuery = ""; searchSummary = null }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                    }
                                }
                                if (findQuery.isNotBlank()) {
                                    IconButton(onClick = { performSearch() }) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.ArrowForward,
                                            contentDescription = stringResource(R.string.action_search),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = replaceText,
                        onValueChange = { replaceText = it },
                        label = { Text(stringResource(R.string.label_replace_with)) },
                        placeholder = { Text(stringResource(R.string.hint_replace_with)) },
                        leadingIcon = { Icon(Icons.Rounded.EditNote, contentDescription = null) },
                        trailingIcon = {
                            if (replaceText.isNotEmpty()) {
                                IconButton(onClick = { replaceText = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = matchCase,
                                onCheckedChange = {
                                    matchCase = it
                                    searchSummary = null
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            )
                            Text(
                                text = stringResource(R.string.label_match_case),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Button(
                            onClick = { performSearch() },
                            enabled = selectedPdfUri != null && findQuery.isNotBlank() && !isSearching
                        ) {
                            Text(stringResource(R.string.action_search))
                        }
                    }
                }
            }

            // Results Section
            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (searchSummary != null) {
                val summary = searchSummary!!
                if (summary.totalMatches == 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_occurrences_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(
                                R.string.search_occurrences_found,
                                summary.totalMatches,
                                summary.pagesAffected
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Button(
                            onClick = {
                                val baseName = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: "document.pdf"
                                val suggestedName = "replaced_$baseName"
                                savePdfLauncher.launch(suggestedName)
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.btn_replace_all_and_save),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(summary.occurrences, key = { "${it.pageIndex}_${it.snippet.hashCode()}" }, contentType = { "occurrence" }) { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "p.${item.pageIndex + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    val annotatedSnippet = remember(item.snippet, findQuery, matchCase) {
                                        buildAnnotatedString {
                                            val snippetText = item.snippet
                                            val query = findQuery.trim()
                                            if (query.isEmpty()) {
                                                append(snippetText)
                                            } else {
                                                var startIndex = 0
                                                while (startIndex < snippetText.length) {
                                                    val index = snippetText.indexOf(query, startIndex, ignoreCase = !matchCase)
                                                    if (index == -1) {
                                                        append(snippetText.substring(startIndex))
                                                        break
                                                    }
                                                    append(snippetText.substring(startIndex, index))
                                                    withStyle(
                                                        SpanStyle(
                                                            background = Color(0xFFFFD54F),
                                                            color = Color.Black,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    ) {
                                                        append(snippetText.substring(index, index + query.length))
                                                    }
                                                    startIndex = index + query.length
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = annotatedSnippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (selectedPdfUri != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("100% Local & Layout-Preserving", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Search for words or phrases across all pages. PDFchemy inspects matches on-device without cloud uploads or rasterizing untouched pages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
