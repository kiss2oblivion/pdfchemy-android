package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.net.Uri
import android.graphics.pdf.PdfRenderer
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PdfStampAndNumberEngine
import com.pdfchemy.app.logic.WatermarkOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var watermarkText by remember { mutableStateOf("CONFIDENTIAL") }
    var opacity by remember { mutableFloatStateOf(0.30f) }
    var rotation by remember { mutableFloatStateOf(45f) }
    var isTiled by remember { mutableStateOf(false) }
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
                val count = com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
                if (count > 0) {
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
                val options = WatermarkOptions(
                    text = watermarkText.trim(),
                    rotationDegrees = rotation,
                    opacity = opacity,
                    isTiled = isTiled
                )
                val success = PdfStampAndNumberEngine.addWatermark(context, selectedPdfUri!!, destUri, options)
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    if (success) {
                        viewModel.notifySuccess(
                            context.getString(R.string.title_watermark_success),
                            context.getString(R.string.desc_watermark_success),
                            destUri
                        )
                        onBack()
                    } else {
                        viewModel.notifyError(context.getString(R.string.error_watermark_failed))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_watermark_pdf), fontWeight = FontWeight.Bold) },
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
                            Icon(Icons.Rounded.BrandingWatermark, contentDescription = null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.watermark_headline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.watermark_subtitle),
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
                                text = stringResource(R.string.select_pdf_to_watermark),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // Page Preview
                if (previewBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
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

                        // Real-Time Watermark Preview Overlay
                        if (watermarkText.isNotBlank()) {
                            if (isTiled) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                    verticalArrangement = Arrangement.SpaceEvenly,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    repeat(3) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            repeat(3) {
                                                Text(
                                                    text = watermarkText,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF424242).copy(alpha = opacity.coerceIn(0.1f, 1f)),
                                                    modifier = Modifier.rotate(rotation)
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = watermarkText,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF424242).copy(alpha = opacity.coerceIn(0.1f, 1f)),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.rotate(rotation)
                                    )
                                }
                            }
                        }
                    }
                }

                // Watermark Text Input
                OutlinedTextField(
                    value = watermarkText,
                    onValueChange = { watermarkText = it },
                    label = { Text(stringResource(R.string.label_watermark_text)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // Quick Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("CONFIDENTIAL", "DRAFT", "COPY", "URGENT").forEach { preset ->
                        AssistChip(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                watermarkText = preset
                            },
                            label = { Text(preset, fontSize = 11.sp) }
                        )
                    }
                }

                // Opacity Slider
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.label_opacity, (opacity * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = opacity,
                        onValueChange = {
                            if ((it * 10).toInt() != (opacity * 10).toInt()) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            opacity = it
                        },
                        valueRange = 0.1f..0.9f
                    )
                }

                // Rotation Angle Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.label_watermark_angle), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0f to "0°", 45f to "45°", -45f to "-45°", 90f to "90°").forEach { (deg, label) ->
                            FilterChip(
                                selected = rotation == deg,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    rotation = deg
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }

                // Tiled Mode Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stringResource(R.string.label_tile_pattern), fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.desc_tile_pattern), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isTiled,
                        onCheckedChange = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isTiled = it
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Save Action Button
                Button(
                    onClick = { savePdfLauncher.launch("watermarked_${System.currentTimeMillis()}.pdf") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = watermarkText.isNotBlank() && !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Rounded.BrandingWatermark, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_apply_watermark),
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
