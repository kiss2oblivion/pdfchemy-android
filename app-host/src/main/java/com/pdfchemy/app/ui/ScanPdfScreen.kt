package com.pdfchemy.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.pdfchemy.app.R

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import com.pdfchemy.app.Screen

enum class ScanFilterMode {
    ORIGINAL,
    MAGIC_COLOR,
    BLACK_AND_WHITE,
    GRAYSCALE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPdfScreen(
    viewModel: MainViewModel,
    onNavigateToTool: (Screen) -> Unit,
    onBack: () -> Unit
) {
    SecureScreenContent()
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var scannedBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var currentFilter by remember { mutableStateOf(ScanFilterMode.MAGIC_COLOR) }
    var isProcessing by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<Screen?>(null) }

    val currentScannedBitmaps by rememberUpdatedState(scannedBitmaps)
    DisposableEffect(Unit) {
        onDispose {
            currentScannedBitmaps.forEach { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    // GMS Document Scanner Launcher
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdfUri = scanResult?.pdf?.uri
            val pageUris = scanResult?.pages?.mapNotNull { it.imageUri } ?: emptyList()

            if (pageUris.isNotEmpty()) {
                coroutineScope.launch(Dispatchers.IO) {
                    val bmps = pageUris.mapNotNull { uri ->
                        decodeBoundedBitmap(context, uri)
                    }
                    withContext(Dispatchers.Main) {
                        if (bmps.isNotEmpty()) {
                            val oldBmps = scannedBitmaps
                            scannedBitmaps = bmps
                            selectedIndex = 0
                            oldBmps.forEach { if (!it.isRecycled) it.recycle() }
                        }
                    }
                }
            } else if (pdfUri != null) {
                // If GMS directly provided PDF
                coroutineScope.launch {
                    val exports = File(context.cacheDir, "exports").apply { mkdirs() }
                    val destFile = File(exports, "scan_${System.currentTimeMillis()}.pdf")
                    try {
                        withContext(Dispatchers.IO) {
                            requireNotNull(context.contentResolver.openInputStream(pdfUri)) { "Cannot read scanned PDF" }.use { input ->
                                destFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
                        if (pendingNavigation != null) {
                            viewModel.setContinuityUri(uri)
                            onNavigateToTool(if (pendingNavigation is Screen.PdfReader) Screen.PdfReader(uri) else pendingNavigation!!)
                        } else {
                            viewModel.notifySuccess(context.getString(R.string.title_scan_success), context.getString(R.string.desc_scan_success), uri)
                            onBack()
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        destFile.delete()
                        throw cancelled
                    } catch (error: Exception) {
                        destFile.delete()
                        viewModel.notifyError(context.getString(R.string.error_scan_failed))
                    }
                }
            }
        }
    }

    // Photo Gallery Fallback Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch(Dispatchers.IO) {
                val bmps = uris.mapNotNull { uri ->
                    decodeBoundedBitmap(context, uri)
                }
                withContext(Dispatchers.Main) {
                    if (bmps.isNotEmpty()) {
                        val oldBmps = scannedBitmaps
                        scannedBitmaps = bmps
                        selectedIndex = 0
                        oldBmps.forEach { if (!it.isRecycled) it.recycle() }
                    }
                }
            }
        }
    }

    // Export PDF Launcher
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && scannedBitmaps.isNotEmpty()) {
            isProcessing = true
            coroutineScope.launch(Dispatchers.IO) {
                val images = mutableListOf<java.io.File>()
                try {
                    for (rawBmp in scannedBitmaps) {
                        val filteredBmp = applyScanFilter(rawBmp, currentFilter)
                        try {
                            val image = java.io.File.createTempFile("scan_", ".png", context.cacheDir)
                            images.add(image)
                            com.pdfchemy.app.security.BoundedOutputStream(image.outputStream(), com.pdfchemy.app.security.SecurityLimits.MAX_PDF_FILESIZE).use { check(filteredBmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        } finally {
                            if (filteredBmp != rawBmp && !filteredBmp.isRecycled) {
                                filteredBmp.recycle()
                            }
                        }
                    }

                    com.pdfchemy.app.logic.PdfGateway.executeEngineBatch(context, "IMAGES_TO_PDF", images.map(android.net.Uri::fromFile), listOf(destUri), "{}")

                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        if (pendingNavigation != null) {
                            viewModel.setContinuityUri(destUri)
                            onNavigateToTool(if (pendingNavigation is Screen.PdfReader) Screen.PdfReader(destUri) else pendingNavigation!!)
                        } else {
                            viewModel.notifySuccess(
                                context.getString(R.string.title_scan_success),
                                context.getString(R.string.desc_scan_success),
                                destUri
                            )
                            onBack()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isProcessing = false
                        viewModel.notifyError(context.getString(R.string.error_scan_failed))
                    }
                } finally {
                    images.forEach { it.delete() }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_scan_document), fontWeight = FontWeight.Bold) },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (scannedBitmaps.isEmpty()) {
                // Empty state / Scanner launch options
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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
                            Icon(
                                Icons.Rounded.DocumentScanner,
                                contentDescription = null,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.scan_camera_headline),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = stringResource(R.string.scan_camera_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                val options = GmsDocumentScannerOptions.Builder()
                                    .setGalleryImportAllowed(true)
                                    .setPageLimit(100)
                                    .setResultFormats(
                                        GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                                        GmsDocumentScannerOptions.RESULT_FORMAT_PDF
                                    )
                                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                                    .build()

                                val client = GmsDocumentScanning.getClient(options)
                                client.getStartScanIntent(context as Activity)
                                    .addOnSuccessListener { intentSender ->
                                        scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                    }
                                    .addOnFailureListener {
                                        // Fallback to image picker if GMS is unavailable
                                        imagePickerLauncher.launch("image/*")
                                    }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 52.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_start_camera_scan),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_import_from_gallery),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // Scanned Pages Preview & Filter Controls
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.05f)),
                    contentAlignment = Alignment.Center
                ) {
                    val currentBmp = scannedBitmaps.getOrNull(selectedIndex)
                    var previewFiltered by remember { mutableStateOf<Bitmap?>(null) }

                    LaunchedEffect(currentBmp, currentFilter) {
                        if (currentBmp != null) {
                            val oldPreview = previewFiltered
                            val filtered = kotlinx.coroutines.withContext(Dispatchers.Default) {
                                applyScanFilter(currentBmp, currentFilter)
                            }
                            previewFiltered = filtered
                            if (oldPreview != null && oldPreview != currentBmp && !oldPreview.isRecycled) {
                                oldPreview.recycle()
                            }
                        } else {
                            previewFiltered = null
                        }
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            val p = previewFiltered
                            if (p != null && !p.isRecycled && !scannedBitmaps.contains(p)) {
                                p.recycle()
                            }
                        }
                    }

                    val displayBmp = previewFiltered ?: currentBmp
                    if (displayBmp != null) {
                        Image(
                            bitmap = displayBmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                // Page Thumbnails Row
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(scannedBitmaps, key = { idx, bmp -> bmp.hashCode() }) { idx, bmp ->
                        Box(
                            modifier = Modifier
                                .size(64.dp, 84.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (selectedIndex == idx) 3.dp else 1.dp,
                                    color = if (selectedIndex == idx) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedIndex = idx }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }

                // Filter Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    ScanFilterMode.values().forEach { mode ->
                        FilterChip(
                            selected = currentFilter == mode,
                            onClick = { currentFilter = mode },
                            label = {
                                Text(
                                    when (mode) {
                                        ScanFilterMode.ORIGINAL -> stringResource(R.string.filter_original)
                                        ScanFilterMode.MAGIC_COLOR -> stringResource(R.string.filter_magic_color)
                                        ScanFilterMode.BLACK_AND_WHITE -> stringResource(R.string.filter_bw)
                                        ScanFilterMode.GRAYSCALE -> stringResource(R.string.filter_grayscale)
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }

                // Post-Capture Continuity Options
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { 
                            pendingNavigation = Screen.PdfReader()
                            savePdfLauncher.launch("scanned_document_${System.currentTimeMillis()}.pdf") 
                        },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        enabled = !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Save & Open in Reader", fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { 
                                pendingNavigation = Screen.OcrPdf
                                savePdfLauncher.launch("scanned_document_${System.currentTimeMillis()}.pdf") 
                            },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isProcessing
                        ) {
                            Icon(Icons.Rounded.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Save & OCR", fontSize = 13.sp)
                        }
                        
                        OutlinedButton(
                            onClick = { 
                                pendingNavigation = Screen.CompressPdf
                                savePdfLauncher.launch("scanned_document_${System.currentTimeMillis()}.pdf") 
                            },
                            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isProcessing
                        ) {
                            Icon(Icons.Rounded.Compress, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Save & Compress", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

fun applyScanFilter(src: Bitmap, filter: ScanFilterMode): Bitmap {
    return when (filter) {
        ScanFilterMode.ORIGINAL -> src
        ScanFilterMode.GRAYSCALE -> {
            val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = Paint()
            val cm = ColorMatrix().apply { setSaturation(0f) }
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(src, 0f, 0f, paint)
            bmp
        }
        ScanFilterMode.MAGIC_COLOR -> {
            val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = Paint()
            val cm = ColorMatrix(
                floatArrayOf(
                    1.2f, 0f, 0f, 0f, -10f,
                    0f, 1.2f, 0f, 0f, -10f,
                    0f, 0f, 1.2f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(src, 0f, 0f, paint)
            bmp
        }
        ScanFilterMode.BLACK_AND_WHITE -> {
            val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = Paint()
            val cm = ColorMatrix(
                floatArrayOf(
                    1.5f, 1.5f, 1.5f, 0f, -160f,
                    1.5f, 1.5f, 1.5f, 0f, -160f,
                    1.5f, 1.5f, 1.5f, 0f, -160f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(src, 0f, 0f, paint)
            bmp
        }
    }
}

private suspend fun decodeBoundedBitmap(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? =
    com.pdfchemy.app.logic.IsolatedImageDecoder.decode(context, uri)
