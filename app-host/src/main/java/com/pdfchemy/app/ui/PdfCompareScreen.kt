package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.DocumentDiffSummary
import com.pdfchemy.app.logic.PdfDiffEngine
import com.pdfchemy.app.utils.FileUtils
import com.pdfchemy.app.utils.ResourceTransferOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CompareViewMode {
    VISUAL_OVERLAY,
    TEXT_CHANGES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfCompareScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    SecureScreenContent()
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val continuityUri by viewModel.continuityDocumentUri.collectAsState()
    var doc1Uri by remember { mutableStateOf<Uri?>(continuityUri) }
    var doc2Uri by remember { mutableStateOf<Uri?>(null) }

    var diffSummary by remember { mutableStateOf<DocumentDiffSummary?>(null) }
    var isComparing by remember { mutableStateOf(false) }
    var comparisonFailed by remember { mutableStateOf(false) }
    var comparisonJob by remember { mutableStateOf<Job?>(null) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var viewMode by remember { mutableStateOf(CompareViewMode.VISUAL_OVERLAY) }

    fun releaseSummary(summary: DocumentDiffSummary?) {
        summary?.close()
    }

    DisposableEffect(Unit) {
        onDispose {
            comparisonJob?.cancel()
            releaseSummary(diffSummary)
        }
    }

    fun runComparison(u1: Uri, u2: Uri) {
        if (isComparing) return
        releaseSummary(diffSummary)
        diffSummary = null
        comparisonFailed = false
        isComparing = true
        comparisonJob = coroutineScope.launch {
            try {
                ResourceTransferOwner<DocumentDiffSummary> { releaseSummary(it) }.use { owner ->
                    withContext(Dispatchers.IO) {
                        owner.stage(PdfDiffEngine.compareDocuments(context, u1, u2))
                    }
                    diffSummary = checkNotNull(owner.commit())
                    currentPageIndex = 0
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                comparisonFailed = true
            } finally {
                isComparing = false
            }
        }
    }

    val doc1PickerLauncher = rememberVanguardPdfPicker { uri ->
        if (isComparing) return@rememberVanguardPdfPicker
        doc1Uri = uri
        val currentDoc2 = doc2Uri
        if (currentDoc2 != null) runComparison(uri, currentDoc2)
    }

    val doc2PickerLauncher = rememberVanguardPdfPicker { uri ->
        if (isComparing) return@rememberVanguardPdfPicker
        doc2Uri = uri
        val currentDoc1 = doc1Uri
        if (currentDoc1 != null) runComparison(currentDoc1, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_compare_pdfs), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    if (doc1Uri != null && doc2Uri != null && !isComparing) {
                        IconButton(
                            onClick = {
                                val u1 = doc2Uri!!
                                val u2 = doc1Uri!!
                                doc1Uri = u1
                                doc2Uri = u2
                                runComparison(u1, u2)
                            }
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = stringResource(R.string.desc_swap_compare_pdfs))
                        }
                    }
                    if (diffSummary != null && !isComparing) {
                        IconButton(
                            onClick = {
                                releaseSummary(diffSummary)
                                diffSummary = null
                            }
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.desc_select_different_pdfs))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (diffSummary == null) {
                // PDF Selection Cards (Original vs Modified)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(stringResource(R.string.compare_headline), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.compare_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Doc 1 (Original)
                            OutlinedCard(
                                modifier = Modifier.weight(1f).clickable(enabled = !isComparing) { doc1PickerLauncher.launch(arrayOf("application/pdf")) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        if (doc1Uri != null) Icons.Rounded.CheckCircle else Icons.Rounded.UploadFile,
                                        contentDescription = null,
                                        tint = if (doc1Uri != null) MaterialTheme.colorScheme.primary else Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        doc1Uri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_original_pdf),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            }

                            // Doc 2 (Modified)
                            OutlinedCard(
                                modifier = Modifier.weight(1f).clickable(enabled = !isComparing) { doc2PickerLauncher.launch(arrayOf("application/pdf")) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        if (doc2Uri != null) Icons.Rounded.CheckCircle else Icons.Rounded.UploadFile,
                                        contentDescription = null,
                                        tint = if (doc2Uri != null) MaterialTheme.colorScheme.secondary else Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        doc2Uri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_modified_pdf),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            }
                        }

                        if (isComparing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                                Text(stringResource(R.string.comparing_documents_progress), style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = { comparisonJob?.cancel() }) { Text(stringResource(R.string.cancel)) }
                        } else if (doc1Uri != null && doc2Uri != null) {
                            if (comparisonFailed) {
                                Text(stringResource(R.string.error_operation_recovery),
                                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            }
                            Button(onClick = {
                                val original = doc1Uri
                                val modified = doc2Uri
                                if (original != null && modified != null) runComparison(original, modified)
                            }) { Text(stringResource(R.string.action_retry)) }
                        }
                    }
                }
            } else {
                val summary = diffSummary!!
                val currentDiff = summary.pageDiffs.getOrNull(currentPageIndex)

                // Comparison Summary Header
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.compare_results_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.compare_summary_counts, summary.modifiedPages, summary.identicalPages),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // View Mode Toggle
                        SingleChoiceSegmentedButtonRow {
                            SegmentedButton(
                                selected = viewMode == CompareViewMode.VISUAL_OVERLAY,
                                onClick = { viewMode = CompareViewMode.VISUAL_OVERLAY },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) {
                                Icon(Icons.Rounded.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            SegmentedButton(
                                selected = viewMode == CompareViewMode.TEXT_CHANGES,
                                onClick = { viewMode = CompareViewMode.TEXT_CHANGES },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) {
                                Icon(Icons.Rounded.Difference, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Physical geometry is separate from raster difference percentage.
                // Show both displayed page sizes and rotations when page boxes differ.
                if (currentDiff?.geometryChanged == true) {
                    val a = currentDiff.geometry1
                    val b = currentDiff.geometry2
                    if (a != null && b != null) {
                        val before = String.format(java.util.Locale.getDefault(), "%.2f × %.2f pt · %d°",
                            a.widthPoints, a.heightPoints, a.rotation)
                        val after = String.format(java.util.Locale.getDefault(), "%.2f × %.2f pt · %d°",
                            b.widthPoints, b.heightPoints, b.rotation)
                        Text("${stringResource(R.string.image_result_dimensions)}: $before → $after",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Comparison Content View
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (viewMode == CompareViewMode.VISUAL_OVERLAY) {
                        if (currentDiff != null) {
                            key(summary, currentPageIndex) {
                                LazyComparisonPreview(summary, currentPageIndex)
                            }
                        }
                    } else {
                        // Text Changes Log
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (currentDiff != null && (currentDiff.textAddedLines.isNotEmpty() || currentDiff.textRemovedLines.isNotEmpty())) {
                                if (currentDiff.textAddedLines.isNotEmpty()) {
                                    Text(stringResource(R.string.label_added_lines), fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), fontSize = 13.sp)
                                    for (line in currentDiff.textAddedLines) {
                                        Text("+ $line", color = Color(0xFF1B5E20), fontSize = 12.sp, modifier = Modifier.background(Color(0xFFE8F5E9)).padding(4.dp).fillMaxWidth())
                                    }
                                }

                                if (currentDiff.textRemovedLines.isNotEmpty()) {
                                    Text(stringResource(R.string.label_removed_lines), fontWeight = FontWeight.Bold, color = Color(0xFFC62828), fontSize = 13.sp)
                                    for (line in currentDiff.textRemovedLines) {
                                        Text("- $line", color = Color(0xFFB71C1C), fontSize = 12.sp, modifier = Modifier.background(Color(0xFFFFEBEE)).padding(4.dp).fillMaxWidth())
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.no_text_changes_detected), color = Color.Gray)
                                }
                            }
                        }
                    }
                }

                // Page Navigation Bar
                var showPageJumpDialog by remember { mutableStateOf(false) }
                if (showPageJumpDialog && summary.pageDiffs.size > 1) {
                    PageJumpDialog(
                        currentPageZeroBased = currentPageIndex,
                        totalPages = summary.pageDiffs.size,
                        onDismiss = { showPageJumpDialog = false },
                        onJumpToPage = { targetZeroBased ->
                            currentPageIndex = targetZeroBased.coerceIn(0, summary.pageDiffs.size - 1)
                            showPageJumpDialog = false
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (currentPageIndex > 0) currentPageIndex-- },
                        enabled = currentPageIndex > 0
                    ) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.action_prev_page))
                    }

                    Text(
                        if (currentDiff?.alignmentLabel?.isNotBlank() == true) {
                            "${currentPageIndex + 1} / ${summary.pageDiffs.size} • ${currentDiff.alignmentLabel}"
                        } else {
                            "${currentPageIndex + 1} / ${summary.pageDiffs.size}"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = summary.pageDiffs.size > 1) { showPageJumpDialog = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )

                    IconButton(
                        onClick = { if (currentPageIndex < summary.pageDiffs.size - 1) currentPageIndex++ },
                        enabled = currentPageIndex < summary.pageDiffs.size - 1
                    ) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.action_next_page))
                    }
                }
            }
        }
    }
}

/** At most one overlay is displayed. Old requests are cancelled on page/mode changes.
 * ResourceTransferOwner disposes a bitmap even if dispatcher resumption is cancelled. */
@Composable
private fun LazyComparisonPreview(summary: DocumentDiffSummary, index: Int) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        onDispose { bitmap?.let { if (!it.isRecycled) it.recycle() } }
    }
    LaunchedEffect(retry) {
        failed = false
        try {
            ResourceTransferOwner<Bitmap> { if (!it.isRecycled) it.recycle() }.use { owner ->
                withContext(Dispatchers.IO) {
                    owner.stage(summary.renderPreview(context, index))
                }
                bitmap = checkNotNull(owner.commit())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val current = bitmap
    when {
        current != null -> Image(
            bitmap = current.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
        )
        failed -> Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.error_operation_recovery), color = Color.Gray,
                textAlign = TextAlign.Center)
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.action_retry)) }
        }
        else -> CircularProgressIndicator()
    }
}
