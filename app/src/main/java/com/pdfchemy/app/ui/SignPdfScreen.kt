package com.pdfchemy.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PlacedSignature
import com.pdfchemy.app.logic.SignatureEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignPdfScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }
    var currentPageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var savedSignatures by remember { mutableStateOf<List<Pair<String, Bitmap>>>(emptyList()) }
    var showSignaturePad by remember { mutableStateOf(false) }

    // Placed signatures on the document
    var placedSignatures by remember { mutableStateOf<List<PlacedSignature>>(emptyList()) }
    var selectedSignatureId by remember { mutableStateOf<String?>(null) }
    var includeDateStamp by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    val currentBitmap by rememberUpdatedState(currentPageBitmap)
    val currentSignatures by rememberUpdatedState(savedSignatures)
    DisposableEffect(Unit) {
        onDispose {
            currentBitmap?.recycle()
            currentSignatures.forEach { try { it.second.recycle() } catch (_: Throwable) {} }
        }
    }

    // PKI State
    var showPkiDialog by remember { mutableStateOf(false) }
    var pkiSignerName by remember { mutableStateOf("") }
    var pkiReason by remember { mutableStateOf("Signed by PDFchemy") }
    var pkiLocation by remember { mutableStateOf("Local Device") }
    
    val pkiSignLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            isSaving = true
            coroutineScope.launch {
                val success = SignatureEngine.applyDigitalSignature(
                    context = context,
                    sourceUri = selectedPdfUri!!,
                    destUri = destUri,
                    signerName = pkiSignerName,
                    reason = pkiReason,
                    location = pkiLocation
                )
                isSaving = false
                if (success) {
                    showPkiDialog = false
                    onBack()
                } else {
                    // Show error, handled silently for now or via Toast
                }
            }
        }
    }

    val postureInfo = com.pdfchemy.app.logic.rememberDevicePosture()
    var forceTabletopMode by remember { mutableStateOf(false) }
    val isTabletopMode = postureInfo.isTabletop || forceTabletopMode

    // Load saved signatures
    LaunchedEffect(Unit) {
        savedSignatures = SignatureEngine.loadSignatures(context)
    }

    // PDF Page Renderer
    fun renderPage(uri: Uri, index: Int) {
        coroutineScope.launch(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                totalPages = com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
                if (index in 0 until totalPages) {
                    val bmp = com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderPageToBitmap(context, pfd, index, 1080)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            currentPageBitmap?.recycle()
                            currentPageBitmap = bmp
                        }
                    }
                }
            } catch (e: Exception) {
                com.pdfchemy.app.utils.AppLogger.e("Failed to render PDF page: ${e.message}", e)
            } finally {
                try { pfd?.close() } catch (_: Throwable) {}
            }
        }
    }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        currentPageIndex = 0
        placedSignatures = emptyList()
        renderPage(uri, 0)
    }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            isSaving = true
            viewModel.applySignatures(context, selectedPdfUri!!, destUri, placedSignatures) { success ->
                isSaving = false
                if (success) {
                    onBack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_sign_pdf), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = { forceTabletopMode = !forceTabletopMode }) {
                        Icon(
                            imageVector = if (isTabletopMode) Icons.Rounded.LaptopMac else Icons.Rounded.PhoneAndroid,
                            contentDescription = stringResource(if (isTabletopMode) R.string.flip_fullscreen_mode else R.string.flip_tabletop_mode),
                            tint = if (isTabletopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            if (selectedPdfUri == null) {
                // Empty PDF Picker
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
                            Icon(Icons.Rounded.Draw, contentDescription = null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.sign_pdf_headline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.sign_pdf_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.select_pdf_to_sign), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Interactive Sign Canvas & Toolbar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .shadow(8.dp, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentPageBitmap != null) {
                        val bmp = currentPageBitmap!!
                        val pageAspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                        var pagePixelSize by remember { mutableStateOf(IntSize.Zero) }
                        val density = LocalDensity.current

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(pageAspect)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .onSizeChanged { pagePixelSize = it }
                                .pointerInput(pagePixelSize) {
                                    detectTapGestures {
                                        selectedSignatureId = null
                                    }
                                }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Render Placed Signatures on Current Page
                            val pageSignatures = placedSignatures.filter { it.pageIndex == currentPageIndex }
                            for (sig in pageSignatures) {
                                val sigBmp = remember(sig) { BitmapFactory.decodeByteArray(sig.bitmapBytes, 0, sig.bitmapBytes.size) }
                                if (sigBmp != null && pagePixelSize.width > 0 && pagePixelSize.height > 0) {
                                    val isSelected = sig.id == selectedSignatureId

                                    val sigW = (sig.widthRatio * pagePixelSize.width).coerceAtLeast(40f)
                                    val sigH = (sig.heightRatio * pagePixelSize.height).coerceAtLeast(20f)
                                    val widthDp = with(density) { sigW.toDp() }
                                    val heightDp = with(density) { sigH.toDp() }

                                    val xPx = (sig.xRatio * pagePixelSize.width).roundToInt()
                                    val yPx = (sig.yRatio * pagePixelSize.height).roundToInt()

                                    Box(
                                        modifier = Modifier
                                            .offset { IntOffset(xPx, yPx) }
                                            .size(widthDp, heightDp)
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .pointerInput(sig.id, pagePixelSize) {
                                                detectTapGestures {
                                                    selectedSignatureId = sig.id
                                                }
                                            }
                                            .pointerInput(sig.id, pagePixelSize) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        selectedSignatureId = sig.id
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        if (pagePixelSize.width > 0 && pagePixelSize.height > 0) {
                                                            val dx = dragAmount.x / pagePixelSize.width.toFloat()
                                                            val dy = dragAmount.y / pagePixelSize.height.toFloat()
                                                            placedSignatures = placedSignatures.map { item ->
                                                                if (item.id == sig.id) {
                                                                    val newX = (item.xRatio + dx).coerceIn(0f, 1f - item.widthRatio)
                                                                    val newY = (item.yRatio + dy).coerceIn(0f, 1f - item.heightRatio)
                                                                    item.copy(xRatio = newX, yRatio = newY)
                                                                } else item
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                    ) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            Image(
                                                bitmap = sigBmp.asImageBitmap(),
                                                contentDescription = null,
                                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                                contentScale = ContentScale.Fit
                                            )
                                            if (!sig.dateStamp.isNullOrBlank()) {
                                                Text(
                                                    text = "Signed: ${sig.dateStamp}",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Black,
                                                    modifier = Modifier.padding(horizontal = 2.dp)
                                                )
                                            }
                                        }

                                        // Delete badge if selected
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 6.dp, y = (-6).dp)
                                                    .size(22.dp)
                                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                                                    .clickable {
                                                        placedSignatures = placedSignatures.filter { it.id != sig.id }
                                                        if (selectedSignatureId == sig.id) {
                                                            selectedSignatureId = null
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = "Remove signature",
                                                    tint = MaterialTheme.colorScheme.onError,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isTabletopMode) {
                    // Tabletop Hinge Divider
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            Text(
                                text = stringResource(R.string.flip_tabletop_active_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "${currentPageIndex + 1} / $totalPages",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Page Navigation Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (currentPageIndex > 0) {
                                currentPageIndex--
                                renderPage(selectedPdfUri!!, currentPageIndex)
                            }
                        },
                        enabled = currentPageIndex > 0
                    ) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
                    }

                    Text("${currentPageIndex + 1} / $totalPages", fontWeight = FontWeight.Bold)

                    IconButton(
                        onClick = {
                            if (currentPageIndex < totalPages - 1) {
                                currentPageIndex++
                                renderPage(selectedPdfUri!!, currentPageIndex)
                            }
                        },
                        enabled = currentPageIndex < totalPages - 1
                    ) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                }

                // Quick Tools & Business Stamps Tray
                Text(stringResource(R.string.label_quick_tools_stamps), style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.Start))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { showPkiDialog = true },
                            label = { Text("🔒 PKI Cert", fontWeight = FontWeight.Bold, color = Color(0xFF059669)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val bmp = SignatureEngine.createMarkBitmap("✓", "#16A34A")
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val newSig = PlacedSignature(
                                    pageIndex = currentPageIndex,
                                    xRatio = 0.45f,
                                    yRatio = 0.45f,
                                    widthRatio = 0.08f,
                                    heightRatio = 0.05f,
                                    bitmapBytes = stream.toByteArray()
                                )
                                placedSignatures = placedSignatures + newSig
                                selectedSignatureId = newSig.id
                            },
                            label = { Text("✓ Check", fontWeight = FontWeight.Bold, color = Color(0xFF16A34A)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val bmp = SignatureEngine.createMarkBitmap("✕", "#DC2626")
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val newSig = PlacedSignature(
                                    pageIndex = currentPageIndex,
                                    xRatio = 0.45f,
                                    yRatio = 0.45f,
                                    widthRatio = 0.08f,
                                    heightRatio = 0.05f,
                                    bitmapBytes = stream.toByteArray()
                                )
                                placedSignatures = placedSignatures + newSig
                                selectedSignatureId = newSig.id
                            },
                            label = { Text("✕ Cross", fontWeight = FontWeight.Bold, color = Color(0xFFDC2626)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                                val bmp = SignatureEngine.createBusinessStampBitmap(dateStr, "DATE VERIFIED", "#1E3A8A", width = 280, height = 90)
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val newSig = PlacedSignature(
                                    pageIndex = currentPageIndex,
                                    xRatio = 0.35f,
                                    yRatio = 0.70f,
                                    widthRatio = 0.28f,
                                    heightRatio = 0.08f,
                                    bitmapBytes = stream.toByteArray()
                                )
                                placedSignatures = placedSignatures + newSig
                                selectedSignatureId = newSig.id
                            },
                            label = { Text("📅 Date", fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val bmp = SignatureEngine.createBusinessStampBitmap("CONFORM CU ORIGINALUL", "ORIGINAL VERIFIED", "#1E3A8A")
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val newSig = PlacedSignature(
                                    pageIndex = currentPageIndex,
                                    xRatio = 0.35f,
                                    yRatio = 0.70f,
                                    widthRatio = 0.35f,
                                    heightRatio = 0.10f,
                                    bitmapBytes = stream.toByteArray()
                                )
                                placedSignatures = placedSignatures + newSig
                                selectedSignatureId = newSig.id
                            },
                            label = { Text("CONFORM CU ORIGINALUL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A8A)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val bmp = SignatureEngine.createBusinessStampBitmap("APPROVED", "OFFICIAL APPROVAL", "#16A34A")
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val newSig = PlacedSignature(
                                    pageIndex = currentPageIndex,
                                    xRatio = 0.35f,
                                    yRatio = 0.70f,
                                    widthRatio = 0.30f,
                                    heightRatio = 0.09f,
                                    bitmapBytes = stream.toByteArray()
                                )
                                placedSignatures = placedSignatures + newSig
                                selectedSignatureId = newSig.id
                            },
                            label = { Text("APPROVED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val bmp = SignatureEngine.createBusinessStampBitmap("CONFIDENTIAL", "INTERNAL USE ONLY", "#DC2626")
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val newSig = PlacedSignature(
                                    pageIndex = currentPageIndex,
                                    xRatio = 0.35f,
                                    yRatio = 0.70f,
                                    widthRatio = 0.32f,
                                    heightRatio = 0.09f,
                                    bitmapBytes = stream.toByteArray()
                                )
                                placedSignatures = placedSignatures + newSig
                                selectedSignatureId = newSig.id
                            },
                            label = { Text("CONFIDENTIAL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val bmp = SignatureEngine.createBusinessStampBitmap("PAID", "PAYMENT SETTLED", "#7C3AED")
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val newSig = PlacedSignature(
                                    pageIndex = currentPageIndex,
                                    xRatio = 0.35f,
                                    yRatio = 0.70f,
                                    widthRatio = 0.26f,
                                    heightRatio = 0.08f,
                                    bitmapBytes = stream.toByteArray()
                                )
                                placedSignatures = placedSignatures + newSig
                                selectedSignatureId = newSig.id
                            },
                            label = { Text("PAID", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    item {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val bmp = SignatureEngine.createBusinessStampBitmap("DRAFT", "SUBJECT TO REVISION", "#D97706")
                                val stream = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                val newSig = PlacedSignature(
                                    pageIndex = currentPageIndex,
                                    xRatio = 0.35f,
                                    yRatio = 0.70f,
                                    widthRatio = 0.26f,
                                    heightRatio = 0.08f,
                                    bitmapBytes = stream.toByteArray()
                                )
                                placedSignatures = placedSignatures + newSig
                                selectedSignatureId = newSig.id
                            },
                            label = { Text("DRAFT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD97706)) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                // Saved Signatures Tray
                Text(stringResource(R.string.label_saved_signatures), style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.Start))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        OutlinedButton(
                            onClick = { showSignaturePad = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_new_signature))
                        }
                    }

                    items(savedSignatures, key = { it.first }, contentType = { "sigItem" }) { (name, bmp) ->
                        ElevatedCard(
                            modifier = Modifier
                                .size(100.dp, 56.dp)
                                .clickable {
                                    // Place signature in the center of the current page
                                    val stream = ByteArrayOutputStream()
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                    val dateStr = if (includeDateStamp) SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) else null
                                    val newSig = PlacedSignature(
                                        pageIndex = currentPageIndex,
                                        xRatio = 0.35f,
                                        yRatio = 0.70f,
                                        widthRatio = 0.30f,
                                        heightRatio = 0.12f,
                                        bitmapBytes = stream.toByteArray(),
                                        dateStamp = dateStr
                                    )
                                    placedSignatures = placedSignatures + newSig
                                    selectedSignatureId = newSig.id
                                },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = name, contentScale = ContentScale.Fit)
                            }
                        }
                    }
                }

                // Save Action Button
                Button(
                    onClick = { savePdfLauncher.launch("signed_document_${System.currentTimeMillis()}.pdf") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = placedSignatures.isNotEmpty() && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_save_signed_pdf, placedSignatures.size),
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

    // Signature Drawing Pad Bottom Sheet
    if (showSignaturePad) {
        ModalBottomSheet(onDismissRequest = { showSignaturePad = false }) {
            var signaturePaths by remember { mutableStateOf<List<Path>>(emptyList()) }
            var currentPath by remember { mutableStateOf<Path?>(null) }
            var strokePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.title_draw_signature), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF7F8FA))
                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val p = Path().apply { moveTo(offset.x, offset.y) }
                                    currentPath = p
                                    strokePoints = listOf(offset)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    strokePoints = strokePoints + change.position
                                    val p = Path()
                                    if (strokePoints.isNotEmpty()) {
                                        p.moveTo(strokePoints[0].x, strokePoints[0].y)
                                        for (i in 1 until strokePoints.size) {
                                            p.lineTo(strokePoints[i].x, strokePoints[i].y)
                                        }
                                    }
                                    currentPath = p
                                },
                                onDragEnd = {
                                    if (currentPath != null) {
                                        signaturePaths = signaturePaths + currentPath!!
                                    }
                                    currentPath = null
                                    strokePoints = emptyList()
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (path in signaturePaths) {
                            drawPath(path, color = Color.Black, style = Stroke(width = 5f))
                        }
                        currentPath?.let {
                            drawPath(it, color = Color.Black, style = Stroke(width = 5f))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        signaturePaths = emptyList()
                        currentPath = null
                    }) {
                        Text(stringResource(R.string.action_clear), color = MaterialTheme.colorScheme.error)
                    }

                    Button(
                        onClick = {
                            if (signaturePaths.isNotEmpty()) {
                                val bmp = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bmp)
                                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = android.graphics.Color.BLACK
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = 6f
                                }
                                for (p in signaturePaths) {
                                    canvas.drawPath(p.asAndroidPath(), paint)
                                }

                                coroutineScope.launch {
                                    val sigName = "sig_${System.currentTimeMillis()}"
                                    SignatureEngine.saveSignature(context, sigName, bmp)
                                    savedSignatures = SignatureEngine.loadSignatures(context)
                                    showSignaturePad = false
                                }
                            }
                        },
                        enabled = signaturePaths.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.action_save_signature))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showPkiDialog) {
        AlertDialog(
            onDismissRequest = { showPkiDialog = false },
            title = { Text("Apply PKI Digital Signature") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Applies a self-signed digital certificate cryptographically verifying document integrity.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = pkiSignerName,
                        onValueChange = { pkiSignerName = it },
                        label = { Text("Signer Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pkiReason,
                        onValueChange = { pkiReason = it },
                        label = { Text("Reason") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pkiLocation,
                        onValueChange = { pkiLocation = it },
                        label = { Text("Location") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { pkiSignLauncher.launch("signed_document_pki_${System.currentTimeMillis()}.pdf") },
                    enabled = pkiSignerName.isNotBlank() && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Sign & Save")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showPkiDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
