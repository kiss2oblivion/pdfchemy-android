package com.pdfchemy.app.ui

import android.net.Uri
import android.graphics.pdf.PdfRenderer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.pdfchemy.app.logic.BookletSheetPlan
import com.pdfchemy.app.logic.BookletPlanner
import com.pdfchemy.app.logic.PdfGateway
import org.json.JSONObject
import com.pdfchemy.app.logic.TargetPaperSize
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookletScreen(
    viewModel: MainViewModel,
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var pageCount by remember { mutableIntStateOf(0) }
    var bookletPlan by remember { mutableStateOf<List<BookletSheetPlan>>(emptyList()) }
    var selectedPaperSize by remember { mutableStateOf(TargetPaperSize.A4) }
    var drawFoldGuide by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedPdfUri) {
        val uri = selectedPdfUri
        if (uri != null) {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val count = com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
                        withContext(Dispatchers.Main) {
                            pageCount = count
                            bookletPlan = BookletPlanner.computeBookletPlan(count)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            coroutineScope.launch {
                isProcessing = true
                try {
                    val params = JSONObject()
                        .put("paperHeightPts", selectedPaperSize.heightPts)
                        .put("paperWidthPts", selectedPaperSize.widthPts)
                        .put("drawFoldGuide", drawFoldGuide)
                    PdfGateway.executeEngine(context, "BOOKLET_GENERATE", selectedPdfUri!!, destUri, params.toString())
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_booklet_success),
                        context.getString(R.string.desc_booklet_success)
                    )
                } catch (e: Exception) {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_booklet_failed),
                        e.localizedMessage ?: ""
                    )
                } finally {
                    isProcessing = false
                }
                
                if (false) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_booklet_success),
                        context.getString(R.string.desc_booklet_success)
                    )
                    onBack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_booklet_creator), fontWeight = FontWeight.Bold) },
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
            // Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.booklet_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.booklet_subtitle),
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
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_pdf_for_booklet),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (selectedPdfUri != null) stringResource(R.string.label_pages_detected, pageCount)
                            else stringResource(R.string.tap_to_browse),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }

            // Imposition Plan preview
            if (bookletPlan.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.label_imposition_plan),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.desc_imposition_sheets, bookletPlan.size / 2, bookletPlan.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        bookletPlan.take(6).forEach { side ->
                            val sideName = if (side.isFront) stringResource(R.string.sheet_front) else stringResource(R.string.sheet_back)
                            val leftStr = side.leftPageOriginalIndex?.let { "P${it + 1}" } ?: stringResource(R.string.page_blank)
                            val rightStr = side.rightPageOriginalIndex?.let { "P${it + 1}" } ?: stringResource(R.string.page_blank)

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Sheet ${side.sheetNumber} ($sideName)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("[$leftStr | $rightStr]", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (bookletPlan.size > 6) {
                            Text(
                                stringResource(R.string.label_more_sheets, bookletPlan.size - 6),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Settings (Paper Size & Fold guideline)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.label_booklet_settings), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.opt_center_fold_guide), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.desc_center_fold_guide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = drawFoldGuide, onCheckedChange = { drawFoldGuide = it })
                    }
                }
            }

            // Progress
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

            // Action
            Button(
                onClick = {
                    val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                    saveFileLauncher.launch("${base}_booklet.pdf")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                enabled = !isProcessing && selectedPdfUri != null && pageCount > 0
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_create_booklet),
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
