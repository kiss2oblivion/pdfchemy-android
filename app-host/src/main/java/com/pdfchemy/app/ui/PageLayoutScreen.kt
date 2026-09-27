package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.NUpMode
import com.pdfchemy.app.logic.PdfLayoutEngine
import com.pdfchemy.app.logic.TargetPaperSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LayoutToolTab {
    RESIZE,
    N_UP_HANDOUT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageLayoutScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var totalPages by remember { mutableIntStateOf(1) }

    var selectedTab by remember { mutableStateOf(LayoutToolTab.N_UP_HANDOUT) }
    var targetSize by remember { mutableStateOf(TargetPaperSize.A4) }
    var nUpMode by remember { mutableStateOf(NUpMode.TWO_UP) }
    var drawBorders by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }

    val currentPreviewBitmap by rememberUpdatedState(previewBitmap)
    DisposableEffect(Unit) {
        onDispose {
            currentPreviewBitmap?.recycle()
        }
    }

    fun loadPreview(uri: Uri) {
        coroutineScope.launch(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                totalPages = com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
                if (totalPages > 0) {
                    val bmp = com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderPageToBitmap(context, pfd, 0, 1080)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            previewBitmap?.recycle()
                            previewBitmap = bmp
                        }
                    }
                }
            } catch (e: Exception) {
                com.pdfchemy.app.utils.AppLogger.e("Failed to load layout preview: ${e.message}", e)
            } finally {
                try { pfd?.close() } catch (_: Throwable) {}
            }
        }
    }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        loadPreview(uri)
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            isProcessing = true
            coroutineScope.launch(Dispatchers.IO) {
                val success = when (selectedTab) {
                    LayoutToolTab.RESIZE -> PdfLayoutEngine.resizePages(context, selectedPdfUri!!, destUri, targetSize)
                    LayoutToolTab.N_UP_HANDOUT -> PdfLayoutEngine.createNUpLayout(context, selectedPdfUri!!, destUri, nUpMode, targetSize, drawBorders)
                }

                withContext(Dispatchers.Main) {
                    isProcessing = false
                    if (success) {
                        viewModel.notifySuccess(
                            context.getString(R.string.title_layout_success),
                            context.getString(R.string.desc_layout_success),
                            destUri
                        )
                        onBack()
                    } else {
                        viewModel.notifyError(context.getString(R.string.error_layout_failed))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_page_layout), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (selectedPdfUri == null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Dashboard, contentDescription = null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.page_layout_headline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.page_layout_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.select_pdf_for_layout),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // Tab Selection (Resize vs N-Up)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedTab == LayoutToolTab.N_UP_HANDOUT,
                        onClick = { selectedTab = LayoutToolTab.N_UP_HANDOUT },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_nup_handout))
                    }
                    SegmentedButton(
                        selected = selectedTab == LayoutToolTab.RESIZE,
                        onClick = { selectedTab = LayoutToolTab.RESIZE },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.tab_resize_paper))
                    }
                }

                // Page Preview
                if (previewBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .shadow(6.dp, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                if (selectedTab == LayoutToolTab.N_UP_HANDOUT) {
                    // N-Up Mode Choice
                    Text(stringResource(R.string.label_pages_per_sheet), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            NUpMode.TWO_UP to stringResource(R.string.mode_2_up),
                            NUpMode.FOUR_UP to stringResource(R.string.mode_4_up),
                            NUpMode.SIX_UP to stringResource(R.string.mode_6_up)
                        ).forEach { (m, label) ->
                            FilterChip(
                                selected = nUpMode == m,
                                onClick = { nUpMode = m },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    // Draw Borders Switch
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.label_draw_page_borders), fontWeight = FontWeight.Bold)
                        Switch(checked = drawBorders, onCheckedChange = { drawBorders = it })
                    }
                }

                // Target Paper Size Choice
                Text(stringResource(R.string.label_target_paper_size), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TargetPaperSize.values().forEach { size ->
                        FilterChip(
                            selected = targetSize == size,
                            onClick = { targetSize = size },
                            label = { Text(size.displayName, fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Save Action Button
                Button(
                    onClick = { savePdfLauncher.launch("layout_${System.currentTimeMillis()}.pdf") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Rounded.Dashboard, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_generate_layout),
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
}
