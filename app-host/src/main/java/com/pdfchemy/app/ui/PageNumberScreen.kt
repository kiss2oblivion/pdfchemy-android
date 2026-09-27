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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.NumberFormat
import com.pdfchemy.app.logic.NumberPosition
import com.pdfchemy.app.logic.PageNumberOptions
import com.pdfchemy.app.logic.PdfStampAndNumberEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageNumberScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var totalPages by remember { mutableIntStateOf(1) }

    var selectedFormat by remember { mutableStateOf(NumberFormat.PAGE_X_OF_Y) }
    var selectedPosition by remember { mutableStateOf(NumberPosition.BOTTOM_CENTER) }
    var skipFirstPage by remember { mutableStateOf(false) }
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
                com.pdfchemy.app.utils.AppLogger.e("Failed to render preview: ${e.message}", e)
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
                val options = PageNumberOptions(
                    position = selectedPosition,
                    format = selectedFormat,
                    skipFirstPage = skipFirstPage
                )
                val success = PdfStampAndNumberEngine.addPageNumbers(context, selectedPdfUri!!, destUri, options)
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    if (success) {
                        viewModel.notifySuccess(
                            context.getString(R.string.title_page_number_success),
                            context.getString(R.string.desc_page_number_success),
                            destUri
                        )
                        onBack()
                    } else {
                        viewModel.notifyError(context.getString(R.string.error_page_number_failed))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_page_number_pdf), fontWeight = FontWeight.Bold) },
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
                            Icon(Icons.Rounded.Numbers, contentDescription = null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.page_number_headline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.page_number_subtitle),
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
                                text = stringResource(R.string.select_pdf_to_number),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // Page Preview with Live Number Placement Overlay
                if (previewBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
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

                        // Visual Live Page Number Indicator
                        val overlayAlignment = when (selectedPosition) {
                            NumberPosition.TOP_LEFT -> Alignment.TopStart
                            NumberPosition.TOP_CENTER -> Alignment.TopCenter
                            NumberPosition.TOP_RIGHT -> Alignment.TopEnd
                            NumberPosition.BOTTOM_LEFT -> Alignment.BottomStart
                            NumberPosition.BOTTOM_CENTER -> Alignment.BottomCenter
                            NumberPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
                        }

                        val sampleNumberText = when (selectedFormat) {
                            NumberFormat.PAGE_X_OF_Y -> "Page 1 of $totalPages"
                            NumberFormat.SLASH -> "1 / $totalPages"
                            NumberFormat.SIMPLE -> "1"
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentAlignment = overlayAlignment
                        ) {
                            Surface(
                                color = if (skipFirstPage) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(6.dp),
                                shadowElevation = 2.dp
                            ) {
                                Text(
                                    text = if (skipFirstPage) "$sampleNumberText (Cover: Skipped)" else sampleNumberText,
                                    color = if (skipFirstPage) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                // Number Format Choice
                Text(stringResource(R.string.label_number_format), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        NumberFormat.PAGE_X_OF_Y to "Page 1 of $totalPages",
                        NumberFormat.SLASH to "1 / $totalPages",
                        NumberFormat.SIMPLE to "1"
                    ).forEach { (fmt, label) ->
                        FilterChip(
                            selected = selectedFormat == fmt,
                            onClick = {
                                selectedFormat = fmt
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }

                // Position Grid
                Text(stringResource(R.string.label_number_position), fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.label_top_header), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            FilterChip(
                                selected = selectedPosition == NumberPosition.TOP_LEFT,
                                onClick = {
                                    selectedPosition = NumberPosition.TOP_LEFT
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                label = { Text(stringResource(R.string.pos_left), fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = selectedPosition == NumberPosition.TOP_CENTER,
                                onClick = {
                                    selectedPosition = NumberPosition.TOP_CENTER
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                label = { Text(stringResource(R.string.pos_center), fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = selectedPosition == NumberPosition.TOP_RIGHT,
                                onClick = {
                                    selectedPosition = NumberPosition.TOP_RIGHT
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                label = { Text(stringResource(R.string.pos_right), fontSize = 11.sp) }
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Text(stringResource(R.string.label_bottom_footer), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            FilterChip(
                                selected = selectedPosition == NumberPosition.BOTTOM_LEFT,
                                onClick = {
                                    selectedPosition = NumberPosition.BOTTOM_LEFT
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                label = { Text(stringResource(R.string.pos_left), fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = selectedPosition == NumberPosition.BOTTOM_CENTER,
                                onClick = {
                                    selectedPosition = NumberPosition.BOTTOM_CENTER
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                label = { Text(stringResource(R.string.pos_center), fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = selectedPosition == NumberPosition.BOTTOM_RIGHT,
                                onClick = {
                                    selectedPosition = NumberPosition.BOTTOM_RIGHT
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                label = { Text(stringResource(R.string.pos_right), fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // Skip Cover Page Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.label_skip_cover_page), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.desc_skip_cover_page), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = skipFirstPage,
                        onCheckedChange = {
                            skipFirstPage = it
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Save Action Button
                Button(
                    onClick = { savePdfLauncher.launch("numbered_${System.currentTimeMillis()}.pdf") },
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
                        Icon(Icons.Rounded.Numbers, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_apply_page_numbers),
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
