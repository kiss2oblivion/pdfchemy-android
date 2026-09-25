package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.NormalizedCropRect
import com.pdfchemy.app.logic.PdfCropEngine
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageCropperScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    var cropRect by remember { mutableStateOf(NormalizedCropRect(0.05f, 0.05f, 0.95f, 0.95f)) }
    var applyToAllPages by remember { mutableStateOf(true) }

    val currentPreviewBitmap by rememberUpdatedState(previewBitmap)
    DisposableEffect(Unit) {
        onDispose {
            currentPreviewBitmap?.recycle()
        }
    }

    fun loadPagePreview(uri: Uri, pageIndex: Int) {
        coroutineScope.launch(Dispatchers.IO) {
            isRendering = true
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                pageCount = com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
                if (pageCount > 0) {
                    val safeIdx = pageIndex.coerceIn(0, pageCount - 1)
                    currentPageIndex = safeIdx
                    // The original code used scale=2 on original page width, let's just request ~1000px width which is approx 2x
                    val bmp = com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderPageToBitmap(context, pfd, safeIdx, 1000)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            previewBitmap?.recycle()
                            previewBitmap = bmp
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("PageCropperScreen: Failed to render preview", e)
            } finally {
                try { pfd?.close() } catch (_: Throwable) {}
                withContext(Dispatchers.Main) {
                    isRendering = false
                }
            }
        }
    }

    LaunchedEffect(initialPdfUri) {
        initialPdfUri?.let {
            selectedPdfUri = it
            loadPagePreview(it, 0)
        }
    }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        currentPageIndex = 0
        cropRect = NormalizedCropRect(0.05f, 0.05f, 0.95f, 0.95f)
        loadPagePreview(uri, 0)
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            coroutineScope.launch {
                isProcessing = true
                val targetPage = if (applyToAllPages) null else currentPageIndex
                val result = PdfCropEngine.cropPdf(
                    context = context,
                    sourcePdfUri = selectedPdfUri!!,
                    destPdfUri = destUri,
                    cropRect = cropRect,
                    targetPageIndex = targetPage
                )
                isProcessing = false
                if (result.isSuccess) {
                    viewModel.showSuccessToast(
                        context.getString(R.string.title_crop_success),
                        context.getString(R.string.desc_crop_success)
                    )
                    onBack()
                } else {
                    viewModel.showErrorToast(
                        context.getString(R.string.error_crop_failed),
                        result.exceptionOrNull()?.localizedMessage ?: ""
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_crop_pdf), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    if (selectedPdfUri != null) {
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            previewBitmap?.let { bmp ->
                                cropRect = PdfCropEngine.detectContentBounds(bmp)
                            }
                        }) {
                            Icon(
                                Icons.Rounded.AutoFixHigh,
                                contentDescription = stringResource(R.string.btn_auto_crop),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            cropRect = NormalizedCropRect(0.05f, 0.05f, 0.95f, 0.95f)
                        }) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = stringResource(R.string.btn_reset_crop))
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
                        Icons.Rounded.Crop,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.crop_pdf_headline),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.crop_pdf_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // PDF Selection Bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Rounded.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedPdfUri?.let { FileUtils.getFileName(context, it) }
                                ?: stringResource(R.string.select_pdf_for_cropping),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (selectedPdfUri != null) stringResource(R.string.tap_to_change_file)
                            else stringResource(R.string.tap_to_browse),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Interactive Crop Canvas & Page Navigation
            if (selectedPdfUri != null) {
                // Page selector
                if (pageCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (currentPageIndex > 0) {
                                    loadPagePreview(selectedPdfUri!!, currentPageIndex - 1)
                                }
                            },
                            enabled = currentPageIndex > 0 && !isRendering
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_prev_page))
                        }

                        Text(
                            text = stringResource(R.string.label_page_num_of, currentPageIndex + 1, pageCount),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(
                            onClick = {
                                if (currentPageIndex < pageCount - 1) {
                                    loadPagePreview(selectedPdfUri!!, currentPageIndex + 1)
                                }
                            },
                            enabled = currentPageIndex < pageCount - 1 && !isRendering
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.action_next_page))
                        }
                    }
                }

                // Interactive Cropper Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRendering) {
                        CircularProgressIndicator()
                    } else if (previewBitmap != null) {
                        InteractiveCropViewer(
                            bitmap = previewBitmap!!,
                            cropRect = cropRect,
                            onCropChanged = { cropRect = it }
                        )
                    }
                }

                // Quick presets row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            previewBitmap?.let { bmp ->
                                cropRect = PdfCropEngine.detectContentBounds(bmp)
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_auto_crop))
                    }

                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            cropRect = NormalizedCropRect(0.05f, 0.05f, 0.95f, 0.95f)
                        }
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.btn_reset_crop))
                    }
                }

                // Margin Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Margins:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(
                        "Minimal (2%)" to 0.02f,
                        "Standard (5%)" to 0.05f,
                        "Wide (10%)" to 0.10f
                    ).forEach { (label, margin) ->
                        AssistChip(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                cropRect = NormalizedCropRect(margin, margin, 1f - margin, 1f - margin)
                            },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                // Scope Switch
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.label_apply_all_pages),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (applyToAllPages) stringResource(R.string.desc_apply_all_pages)
                                else stringResource(R.string.desc_apply_current_page_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = applyToAllPages,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                applyToAllPages = it
                            }
                        )
                    }
                }

                // Save button
                Button(
                    onClick = {
                        val baseName = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: "document.pdf"
                        val suggestedName = "cropped_$baseName"
                        savePdfLauncher.launch(suggestedName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Rounded.Crop, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.btn_crop_and_save),
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

@Composable
private fun InteractiveCropViewer(
    bitmap: Bitmap,
    cropRect: NormalizedCropRect,
    onCropChanged: (NormalizedCropRect) -> Unit
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val scrimColor = Color.Black.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { viewSize = it.size }
            .pointerInput(viewSize, cropRect) {
                if (viewSize.width == 0 || viewSize.height == 0) return@pointerInput

                val bmpRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val viewRatio = viewSize.width.toFloat() / viewSize.height.toFloat()

                val drawW: Float
                val drawH: Float
                val offsetX: Float
                val offsetY: Float

                if (bmpRatio > viewRatio) {
                    drawW = viewSize.width.toFloat()
                    drawH = drawW / bmpRatio
                    offsetX = 0f
                    offsetY = (viewSize.height - drawH) / 2f
                } else {
                    drawH = viewSize.height.toFloat()
                    drawW = drawH * bmpRatio
                    offsetX = (viewSize.width - drawW) / 2f
                    offsetY = 0f
                }

                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val dx = dragAmount.x / drawW
                    val dy = dragAmount.y / drawH

                    val touchX = (change.position.x - offsetX) / drawW
                    val touchY = (change.position.y - offsetY) / drawH

                    val handleMargin = 0.15f
                    val nearLeft = kotlin.math.abs(touchX - cropRect.left) < handleMargin
                    val nearRight = kotlin.math.abs(touchX - cropRect.right) < handleMargin
                    val nearTop = kotlin.math.abs(touchY - cropRect.top) < handleMargin
                    val nearBottom = kotlin.math.abs(touchY - cropRect.bottom) < handleMargin

                    var newL = cropRect.left
                    var newT = cropRect.top
                    var newR = cropRect.right
                    var newB = cropRect.bottom

                    when {
                        nearLeft && nearTop -> {
                            newL = (cropRect.left + dx).coerceIn(0f, cropRect.right - 0.1f)
                            newT = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - 0.1f)
                        }
                        nearRight && nearTop -> {
                            newR = (cropRect.right + dx).coerceIn(cropRect.left + 0.1f, 1f)
                            newT = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - 0.1f)
                        }
                        nearLeft && nearBottom -> {
                            newL = (cropRect.left + dx).coerceIn(0f, cropRect.right - 0.1f)
                            newB = (cropRect.bottom + dy).coerceIn(cropRect.top + 0.1f, 1f)
                        }
                        nearRight && nearBottom -> {
                            newR = (cropRect.right + dx).coerceIn(cropRect.left + 0.1f, 1f)
                            newB = (cropRect.bottom + dy).coerceIn(cropRect.top + 0.1f, 1f)
                        }
                        nearLeft -> {
                            newL = (cropRect.left + dx).coerceIn(0f, cropRect.right - 0.1f)
                        }
                        nearRight -> {
                            newR = (cropRect.right + dx).coerceIn(cropRect.left + 0.1f, 1f)
                        }
                        nearTop -> {
                            newT = (cropRect.top + dy).coerceIn(0f, cropRect.bottom - 0.1f)
                        }
                        nearBottom -> {
                            newB = (cropRect.bottom + dy).coerceIn(cropRect.top + 0.1f, 1f)
                        }
                        // Inside box -> move whole crop box
                        touchX in cropRect.left..cropRect.right && touchY in cropRect.top..cropRect.bottom -> {
                            val boxW = cropRect.right - cropRect.left
                            val boxH = cropRect.bottom - cropRect.top
                            newL = (cropRect.left + dx).coerceIn(0f, 1f - boxW)
                            newR = newL + boxW
                            newT = (cropRect.top + dy).coerceIn(0f, 1f - boxH)
                            newB = newT + boxH
                        }
                    }

                    onCropChanged(NormalizedCropRect(newL, newT, newR, newB))
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width == 0f || size.height == 0f) return@Canvas

            val bmpRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val canvasRatio = size.width / size.height

            val drawW: Float
            val drawH: Float
            val offsetX: Float
            val offsetY: Float

            if (bmpRatio > canvasRatio) {
                drawW = size.width
                drawH = drawW / bmpRatio
                offsetX = 0f
                offsetY = (size.height - drawH) / 2f
            } else {
                drawH = size.height
                drawW = drawH * bmpRatio
                offsetX = (size.width - drawW) / 2f
                offsetY = 0f
            }

            // 1. Draw page bitmap
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt())
            )

            // 2. Draw dim scrim outside crop box
            val cropPixelLeft = offsetX + cropRect.left * drawW
            val cropPixelTop = offsetY + cropRect.top * drawH
            val cropPixelRight = offsetX + cropRect.right * drawW
            val cropPixelBottom = offsetY + cropRect.bottom * drawH

            // Top scrim
            drawRect(scrimColor, Offset(offsetX, offsetY), Size(drawW, cropPixelTop - offsetY))
            // Bottom scrim
            drawRect(scrimColor, Offset(offsetX, cropPixelBottom), Size(drawW, offsetY + drawH - cropPixelBottom))
            // Left scrim
            drawRect(scrimColor, Offset(offsetX, cropPixelTop), Size(cropPixelLeft - offsetX, cropPixelBottom - cropPixelTop))
            // Right scrim
            drawRect(scrimColor, Offset(cropPixelRight, cropPixelTop), Size(offsetX + drawW - cropPixelRight, cropPixelBottom - cropPixelTop))

            // 3. Draw crop outline
            drawRect(
                color = primaryColor,
                topLeft = Offset(cropPixelLeft, cropPixelTop),
                size = Size(cropPixelRight - cropPixelLeft, cropPixelBottom - cropPixelTop),
                style = Stroke(width = 3.dp.toPx())
            )

            // 4. Draw corner grab handles
            val handleRadius = 8.dp.toPx()
            val handleColor = Color.White
            val corners = listOf(
                Offset(cropPixelLeft, cropPixelTop),
                Offset(cropPixelRight, cropPixelTop),
                Offset(cropPixelLeft, cropPixelBottom),
                Offset(cropPixelRight, cropPixelBottom)
            )

            for (corner in corners) {
                drawCircle(primaryColor, radius = handleRadius + 2.dp.toPx(), center = corner)
                drawCircle(handleColor, radius = handleRadius, center = corner)
            }
        }
    }
}
