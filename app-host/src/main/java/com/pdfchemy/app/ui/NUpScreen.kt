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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.NUpConfig
import com.pdfchemy.app.logic.NUpLayout
import com.pdfchemy.app.logic.NUpOrder
import com.pdfchemy.app.logic.PdfNUpEngine
import com.pdfchemy.app.logic.TargetPaperSize
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NUpScreen(
    viewModel: MainViewModel,
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var selectedLayout by remember { mutableStateOf(NUpLayout.FOUR_UP) }
    var selectedOrder by remember { mutableStateOf(NUpOrder.HORIZONTAL) }
    var drawBorders by remember { mutableStateOf(true) }
    var selectedPaperSize by remember { mutableStateOf(TargetPaperSize.A4) }

    var isProcessing by remember { mutableStateOf(false) }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            coroutineScope.launch {
                isProcessing = true
                val config = NUpConfig(
                    layout = selectedLayout,
                    order = selectedOrder,
                    paperSize = selectedPaperSize,
                    drawBorders = drawBorders
                )

                val result = PdfNUpEngine.generateNUpPdf(
                    context = context,
                    sourcePdfUri = selectedPdfUri!!,
                    destPdfUri = destUri,
                    config = config,
                    onProgress = { c, t ->
                        progressCurrent = c
                        progressTotal = t
                    }
                )
                isProcessing = false

                if (result.isSuccess) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_nup_success),
                        context.getString(R.string.desc_nup_success, selectedLayout.pagesPerSheet)
                    )
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_nup_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_nup_handouts), fontWeight = FontWeight.Bold) },
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
                        Icons.Rounded.GridView,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.nup_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.nup_subtitle),
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
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.select_pdf_for_nup),
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

            // Grid Layout Selection
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.label_pages_per_sheet), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedLayout == NUpLayout.TWO_UP,
                        onClick = { selectedLayout = NUpLayout.TWO_UP },
                        label = { Text("2-Up") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedLayout == NUpLayout.FOUR_UP,
                        onClick = { selectedLayout = NUpLayout.FOUR_UP },
                        label = { Text("4-Up (2×2)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedLayout == NUpLayout.SIX_UP,
                        onClick = { selectedLayout = NUpLayout.SIX_UP },
                        label = { Text("6-Up (2×3)") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedLayout == NUpLayout.NINE_UP,
                        onClick = { selectedLayout = NUpLayout.NINE_UP },
                        label = { Text("9-Up (3×3)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedLayout == NUpLayout.SIXTEEN_UP,
                        onClick = { selectedLayout = NUpLayout.SIXTEEN_UP },
                        label = { Text("16-Up (4×4)") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Page Ordering
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.label_page_ordering), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedOrder == NUpOrder.HORIZONTAL,
                        onClick = { selectedOrder = NUpOrder.HORIZONTAL },
                        label = { Text(stringResource(R.string.opt_order_horizontal)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedOrder == NUpOrder.VERTICAL,
                        onClick = { selectedOrder = NUpOrder.VERTICAL },
                        label = { Text(stringResource(R.string.opt_order_vertical)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Options: Borders and Paper Size
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.opt_draw_slide_borders), fontWeight = FontWeight.Medium)
                        Switch(checked = drawBorders, onCheckedChange = { drawBorders = it })
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

            // Generate Button
            Button(
                onClick = {
                    val base = selectedPdfUri?.let { FileUtils.getFileName(context, it)?.removeSuffix(".pdf") } ?: "document"
                    saveFileLauncher.launch("${base}_${selectedLayout.pagesPerSheet}up_handout.pdf")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                enabled = !isProcessing && selectedPdfUri != null
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.GridView, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_generate_nup_handout),
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
