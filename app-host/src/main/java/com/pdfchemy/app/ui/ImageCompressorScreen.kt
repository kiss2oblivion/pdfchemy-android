package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.BatchImageCompressionResult
import com.pdfchemy.app.logic.FileUtil
import com.pdfchemy.app.logic.ImageAnalysis
import com.pdfchemy.app.logic.ImageCompressionResult
import com.pdfchemy.app.logic.ImageCompressor
import com.pdfchemy.app.logic.ImageOutputFormat
import com.pdfchemy.app.logic.ImageValidationStatus
import com.pdfchemy.app.logic.PerceivedQualityLoss
import com.pdfchemy.app.logic.ShareUtil
import com.pdfchemy.app.utils.FileUtils
import kotlin.math.roundToInt

enum class CompressionMode {
    QUALITY_PERCENT,
    TARGET_SIZE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCompressorScreen(
    viewModel: MainViewModel,
    initialTab: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    BackHandler { onBack() }

    var selectedTab by remember { mutableIntStateOf(initialTab) }
    var compressionMode by remember { mutableStateOf(CompressionMode.QUALITY_PERCENT) }

    var selectedSingleUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBatchUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    var quality by remember { mutableFloatStateOf(0.70f) }
    var selectedTargetSizeMb by remember { mutableStateOf("2") }
    var customTargetMbText by remember { mutableStateOf("") }
    var isCustomTargetSelected by remember { mutableStateOf(false) }

    var outputFormat by remember { mutableStateOf(ImageOutputFormat.ORIGINAL) }
    var maxDimension by remember { mutableIntStateOf(0) }
    var stripExif by remember { mutableStateOf(true) }

    var singleResult by remember { mutableStateOf<ImageCompressionResult?>(null) }
    var lastSavedUri by remember { mutableStateOf<Uri?>(null) }
    var batchResult by remember { mutableStateOf<BatchImageCompressionResult?>(null) }

    // Live Graphic Analysis & Estimator
    val currentTargetSizeBytes: Long? = remember(compressionMode, selectedTargetSizeMb, isCustomTargetSelected, customTargetMbText) {
        if (compressionMode == CompressionMode.TARGET_SIZE) {
            val mbValue = if (isCustomTargetSelected) {
                customTargetMbText.toDoubleOrNull() ?: 2.0
            } else {
                selectedTargetSizeMb.toDoubleOrNull() ?: 2.0
            }
            (mbValue * 1024 * 1024).toLong()
        } else null
    }

    var singleAnalysisState by remember { mutableStateOf<ImageAnalysis?>(null) }
    LaunchedEffect(selectedSingleUri, quality, outputFormat, currentTargetSizeBytes) {
        singleAnalysisState = null
        selectedSingleUri?.let { uri ->
            singleAnalysisState = ImageCompressor.analyzeImage(
                context = context,
                uri = uri,
                quality = (quality * 100).roundToInt(),
                targetFormat = outputFormat,
                targetBytes = currentTargetSizeBytes
            )
        }
    }

    val singleAnalysis = singleAnalysisState
    val resolvedOutputFormat = ImageCompressor.resolveOutputFormat(outputFormat, singleAnalysis?.mimeType.orEmpty())

    // Pickers
    val singlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedSingleUri = uri
            singleResult = null
            lastSavedUri = null
        }
    }

    val batchPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedBatchUris = uris
            batchResult = null
        }
    }

    val saveSingleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(resolvedOutputFormat.mimeType)
    ) { destUri ->
        if (destUri != null && selectedSingleUri != null) {
            if (compressionMode == CompressionMode.TARGET_SIZE && currentTargetSizeBytes != null && currentTargetSizeBytes > 0) {
                viewModel.compressImageToTargetSize(
                    context = context,
                    sourceUri = selectedSingleUri!!,
                    destUri = destUri,
                    targetSizeBytes = currentTargetSizeBytes,
                    format = resolvedOutputFormat,
                    stripExif = stripExif
                ) { res ->
                    singleResult = res
                    if (res.success) lastSavedUri = destUri
                }
            } else {
                val q = (quality * 100).roundToInt().coerceIn(1, 100)
                viewModel.compressImage(
                    context = context,
                    sourceUri = selectedSingleUri!!,
                    destUri = destUri,
                    quality = q,
                    format = resolvedOutputFormat,
                    maxDimension = maxDimension,
                    stripExif = stripExif
                ) { res ->
                    singleResult = res
                    if (res.success) lastSavedUri = destUri
                }
            }
        }
    }

    val batchFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null && selectedBatchUris.isNotEmpty()) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
            if (dir != null && dir.isDirectory) {
                val q = (quality * 100).roundToInt().coerceIn(1, 100)
                viewModel.compressBatchImages(
                    context = context,
                    sourceUris = selectedBatchUris,
                    outputDirectory = dir,
                    quality = q,
                    format = outputFormat,
                    maxDimension = maxDimension,
                    stripExif = stripExif
                ) { bRes ->
                    batchResult = bRes
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_compress_image)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
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
        ) {
            // Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.image_compress_single)) },
                    icon = { Icon(Icons.Rounded.Image, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.image_compress_batch)) },
                    icon = { Icon(Icons.Rounded.Collections, contentDescription = null) }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- FILE SELECTION CARD ---
                item {
                    if (selectedTab == 0) {
                        SingleImageSelectCard(
                            selectedUri = selectedSingleUri,
                            context = context,
                            onPickClick = {
                                singlePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onClearClick = {
                                selectedSingleUri = null
                                singleResult = null
                                lastSavedUri = null
                            }
                        )
                    } else {
                        BatchImagesSelectCard(
                            selectedUris = selectedBatchUris,
                            context = context,
                            onPickClick = {
                                batchPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onClearClick = {
                                selectedBatchUris = emptyList()
                                batchResult = null
                            }
                        )
                    }
                }

                // --- VALIDATION WARNING / ERROR CARD ---
                if (selectedTab == 0 && singleAnalysis != null && singleAnalysis.validationStatus != ImageValidationStatus.ALLOWED) {
                    item {
                        ValidationAlertCard(analysis = singleAnalysis)
                    }
                }

                // --- ESTIMATOR & QUALITY LOSS METER CARD (SINGLE MODE) ---
                if (selectedTab == 0 && singleAnalysis != null && singleAnalysis.isSupported) {
                    item {
                        EstimatorCard(analysis = singleAnalysis)
                    }
                }

                // --- COMPRESSION CONTROLS CARD ---
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.image_compress_options),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Compression Strategy Mode Selector (Single Mode only)
                            if (selectedTab == 0) {
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    SegmentedButton(
                                        selected = compressionMode == CompressionMode.QUALITY_PERCENT,
                                        onClick = { compressionMode = CompressionMode.QUALITY_PERCENT },
                                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                    ) {
                                        Text(stringResource(R.string.mode_quality_slider), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    SegmentedButton(
                                        selected = compressionMode == CompressionMode.TARGET_SIZE,
                                        onClick = { compressionMode = CompressionMode.TARGET_SIZE },
                                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                    ) {
                                        Text(stringResource(R.string.mode_target_size), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (compressionMode == CompressionMode.QUALITY_PERCENT || selectedTab == 1) {
                                // Quality Slider Mode
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.image_compress_quality),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "${(quality * 100).roundToInt()}%",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Slider(
                                        value = quality,
                                        onValueChange = { newValue ->
                                            if ((newValue * 100).roundToInt() != (quality * 100).roundToInt()) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            quality = newValue
                                        },
                                        valueRange = 0.10f..1.00f,
                                        steps = 17,
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )

                                    // Preset Chips
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        PresetChip(label = stringResource(R.string.preset_extreme), active = (quality * 100).roundToInt() == 40) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            quality = 0.40f
                                        }
                                        PresetChip(label = stringResource(R.string.preset_optimal), active = (quality * 100).roundToInt() == 65) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            quality = 0.65f
                                        }
                                        PresetChip(label = stringResource(R.string.preset_crisp), active = (quality * 100).roundToInt() == 85) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            quality = 0.85f
                                        }
                                        PresetChip(label = stringResource(R.string.preset_max), active = (quality * 100).roundToInt() == 100) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            quality = 1.00f
                                        }
                                    }
                                }
                            } else {
                                // Target Size Mode (2MB, 5MB, 10MB, Custom)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.target_size_preset_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf("2", "5", "10").forEach { mb ->
                                            OutlinedButton(
                                                onClick = {
                                                    selectedTargetSizeMb = mb
                                                    isCustomTargetSelected = false
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    containerColor = if (!isCustomTargetSelected && selectedTargetSizeMb == mb) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                                    contentColor = if (!isCustomTargetSelected && selectedTargetSizeMb == mb) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                                )
                                            ) {
                                                Text("$mb MB", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { isCustomTargetSelected = true },
                                            modifier = Modifier.weight(1.2f),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (isCustomTargetSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                                contentColor = if (isCustomTargetSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                        ) {
                                            Text(stringResource(R.string.target_custom), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }

                                    if (isCustomTargetSelected) {
                                        OutlinedTextField(
                                            value = customTargetMbText,
                                            onValueChange = { customTargetMbText = it },
                                            label = { Text(stringResource(R.string.custom_target_mb_label)) },
                                            placeholder = { Text("e.g. 1.5") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Output Format Selector
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.image_output_format),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ImageOutputFormat.values().forEach { fmt ->
                                        FilterChip(
                                            selected = outputFormat == fmt,
                                            onClick = { outputFormat = fmt },
                                            label = { Text(fmt.displayName, fontSize = 12.sp) },
                                            leadingIcon = if (outputFormat == fmt) {
                                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                            } else null
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Max Dimension / Downscale
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.image_max_resolution),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val originalLabel = stringResource(R.string.res_original)
                                val dimOptions = remember(originalLabel) {
                                    listOf(
                                        0 to originalLabel,
                                        3840 to "4K (3840px)",
                                        1920 to "FHD (1920px)",
                                        1280 to "HD (1280px)",
                                        800 to "Web (800px)"
                                    )
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(dimOptions) { (dim, label) ->
                                        FilterChip(
                                            selected = maxDimension == dim,
                                            onClick = { maxDimension = dim },
                                            label = { Text(label, fontSize = 12.sp) }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            // Strip EXIF Metadata Toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { stripExif = !stripExif }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.image_strip_exif),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(R.string.image_strip_exif_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Switch(
                                    checked = stripExif,
                                    onCheckedChange = { stripExif = it }
                                )
                            }
                        }
                    }
                }

                // --- ACTION BUTTON ---
                item {
                    val isSingleAllowed = selectedSingleUri != null && singleAnalysis?.isSupported == true
                    val isBatchAllowed = selectedBatchUris.isNotEmpty()
                    val canProceed = if (selectedTab == 0) isSingleAllowed else isBatchAllowed

                    Button(
                        onClick = {
                            if (selectedTab == 0 && selectedSingleUri != null) {
                                val suggestedName = FileUtil.generateSuggestedName(
                                    selectedSingleUri,
                                    "compressed",
                                    "Image",
                                    resolvedOutputFormat.extension
                                )
                                saveSingleLauncher.launch(suggestedName)
                            } else if (selectedTab == 1 && selectedBatchUris.isNotEmpty()) {
                                batchFolderLauncher.launch(null)
                            }
                        },
                        enabled = canProceed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 54.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Rounded.Compress, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedTab == 0) stringResource(R.string.image_btn_compress) else stringResource(R.string.image_btn_compress_batch, selectedBatchUris.size),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // --- SINGLE RESULT CARD ---
                item {
                    AnimatedVisibility(
                        visible = singleResult != null && singleResult?.success == true,
                        enter = fadeIn() + expandVertically()
                    ) {
                        singleResult?.let { res ->
                            SingleResultCard(
                                result = res,
                                savedUri = lastSavedUri,
                                onShare = {
                                    lastSavedUri?.let { uri ->
                                        val mime = com.pdfchemy.app.utils.FileUtils.getMimeType(context, uri)
                                        ShareUtil.shareFile(context, uri, mime)
                                    }
                                }
                            )
                        }
                    }
                }

                // --- BATCH RESULT CARD ---
                item {
                    AnimatedVisibility(
                        visible = batchResult != null,
                        enter = fadeIn() + expandVertically()
                    ) {
                        batchResult?.let { bRes ->
                            BatchResultCard(result = bRes)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EstimatorCard(analysis: ImageAnalysis) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(20.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f))))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⚡", fontSize = 16.sp)
                    Text(
                        text = stringResource(R.string.estimator_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = "~${analysis.estimatedSavingsPercent}% ${stringResource(R.string.savings_label)}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.estimated_size_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = FileUtils.formatFileSize(analysis.estimatedCompressedBytes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.estimated_quality_loss_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = analysis.qualityLoss.level,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (analysis.qualityLoss == PerceivedQualityLoss.SIGNIFICANT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ValidationAlertCard(analysis: ImageAnalysis) {
    val isDenied = !analysis.isSupported
    val containerBg = if (isDenied) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
    val contentColor = if (isDenied) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
    val icon = if (isDenied) Icons.Rounded.Cancel else Icons.Rounded.WarningAmber

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (isDenied) stringResource(R.string.validation_denied_title) else stringResource(R.string.validation_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = analysis.validationMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.9f)
                )
                val suggestion = analysis.alternativeSuggestion
                if (suggestion != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                }
            }
        }
    }
}

@Composable
fun RowScope.PresetChip(label: String, active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        ),
        border = if (active) ButtonDefaults.outlinedButtonBorder else ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
    ) {
        Text(text = label, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun SingleImageSelectCard(
    selectedUri: Uri?,
    context: Context,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPickClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selectedUri != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        if (selectedUri == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.image_select_single),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.image_select_single_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val fileName = FileUtils.getFileName(context, selectedUri) ?: stringResource(R.string.image_selected_label)
            val fileSize = ImageCompressor.getUriFileSize(context, selectedUri)
            var bounds by remember(selectedUri) { mutableStateOf<ImageCompressor.ImageBounds?>(null) }
            LaunchedEffect(selectedUri) { bounds = ImageCompressor.decodeImageBounds(context, selectedUri) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                IsolatedImage(
                    model = selectedUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${FileUtils.formatFileSize(fileSize)} ${bounds?.let { "• ${it.outWidth}×${it.outHeight} px" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onClearClick) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear))
                }
            }
        }
    }
}

@Composable
fun BatchImagesSelectCard(
    selectedUris: List<Uri>,
    context: Context,
    onPickClick: () -> Unit,
    onClearClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPickClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selectedUris.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        if (selectedUris.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = stringResource(R.string.image_select_batch),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.image_select_batch_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val totalBytes = remember(selectedUris) { selectedUris.sumOf { ImageCompressor.getUriFileSize(context, it) } }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.image_batch_selected_count, selectedUris.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.image_batch_total_size, FileUtils.formatFileSize(totalBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClearClick) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear))
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selectedUris, key = { it.toString() }, contentType = { "imageThumb" }) { uri ->
                        IsolatedImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SingleResultCard(
    result: ImageCompressionResult,
    savedUri: Uri?,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.image_result_success),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Text(
                        text = "-${result.percentSaved}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = stringResource(R.string.label_original), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = FileUtils.formatFileSize(result.originalSize), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = stringResource(R.string.label_compressed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = FileUtils.formatFileSize(result.compressedSize), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }

            Text(
                text = "${stringResource(R.string.image_result_dimensions)}: ${result.width}×${result.height} px • ${result.format}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            if (savedUri != null) {
                Button(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
            }
        }
    }
}

@Composable
fun BatchResultCard(result: BatchImageCompressionResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.image_batch_completed_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = stringResource(
                    R.string.image_batch_completed_summary,
                    result.successCount,
                    result.totalCount,
                    FileUtils.formatFileSize(result.totalOriginalBytes),
                    FileUtils.formatFileSize(result.totalCompressedBytes),
                    result.percentSaved
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
