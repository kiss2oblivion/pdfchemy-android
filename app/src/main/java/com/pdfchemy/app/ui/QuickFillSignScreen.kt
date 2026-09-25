package com.pdfchemy.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.DrawingPath
import com.pdfchemy.app.logic.DrawingPoint
import com.pdfchemy.app.logic.FileUtil
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.TextAnnotation
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class QuickFillTool {
    TEXT,
    CHECKMARK,
    CROSS,
    DATE,
    SIGNATURE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickFillSignScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
    var totalPages by remember { mutableIntStateOf(0) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentPageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRenderingPage by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var selectedTool by remember { mutableStateOf(QuickFillTool.TEXT) }
    var textToPlace by remember { mutableStateOf("John Doe") }
    var showTextDialog by remember { mutableStateOf(false) }
    var showSignDialog by remember { mutableStateOf(false) }
    var savedSignaturePoints by remember { mutableStateOf<List<DrawingPoint>?>(null) }

    // Page modifications map (PageIndex -> PageModification)
    val pageModifications = remember { mutableStateMapOf<Int, PageModification>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun renderCurrentPage(uri: Uri, pageIdx: Int) {
        isRenderingPage = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var pfd: ParcelFileDescriptor? = null
                    try {
                        pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            totalPages = com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
                            if (pageIdx in 0 until totalPages) {
                                val bmp = com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderPageToBitmap(context, pfd, pageIdx, 720)
                                if (bmp != null) {
                                    withContext(Dispatchers.Main) {
                                        currentPageBitmap?.recycle()
                                        currentPageBitmap = bmp
                                    }
                                }
                            }
                        }
                    } finally {
                        pfd?.close()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("QuickFill: Failed to render page $pageIdx", e)
            } finally {
                isRenderingPage = false
            }
        }
    }

    LaunchedEffect(selectedPdfUri, currentPageIndex) {
        selectedPdfUri?.let { renderCurrentPage(it, currentPageIndex) }
    }

    val currentBmp by rememberUpdatedState(currentPageBitmap)
    DisposableEffect(Unit) {
        onDispose {
            currentBmp?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        currentPageIndex = 0
        pageModifications.clear()
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        val srcUri = selectedPdfUri
        if (destUri != null && srcUri != null) {
            isSaving = true
            viewModel.exportEditedPdf(context, srcUri, destUri, pageModifications) { success ->
                isSaving = false
                if (success) {
                    Toast.makeText(context, context.getString(R.string.quick_fill_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun addAnnotationAt(xRatio: Float, yRatio: Float) {
        val currentMod = pageModifications[currentPageIndex] ?: PageModification(pageIndex = currentPageIndex)

        if (selectedTool == QuickFillTool.SIGNATURE) {
            val sigPoints = savedSignaturePoints
            if (sigPoints.isNullOrEmpty()) {
                showSignDialog = true
            } else {
                val localizedPoints = scaleSignatureToPage(sigPoints, xRatio, yRatio)
                val drawing = DrawingPath(
                    points = localizedPoints,
                    color = AndroidColor.BLACK,
                    strokeWidth = 3f
                )
                pageModifications[currentPageIndex] = currentMod.copy(
                    drawings = currentMod.drawings + drawing
                )
            }
            return
        }

        val textItem = when (selectedTool) {
            QuickFillTool.TEXT -> TextAnnotation(
                text = textToPlace,
                xRatio = xRatio.coerceIn(0f, 0.95f),
                yRatio = yRatio.coerceIn(0f, 0.95f),
                fontSize = 15f,
                textColor = AndroidColor.BLACK
            )
            QuickFillTool.CHECKMARK -> TextAnnotation(
                text = "✓",
                xRatio = xRatio.coerceIn(0f, 0.95f),
                yRatio = yRatio.coerceIn(0f, 0.95f),
                fontSize = 20f,
                textColor = AndroidColor.rgb(0, 51, 153) // Navy Blue
            )
            QuickFillTool.CROSS -> TextAnnotation(
                text = "✗",
                xRatio = xRatio.coerceIn(0f, 0.95f),
                yRatio = yRatio.coerceIn(0f, 0.95f),
                fontSize = 20f,
                textColor = AndroidColor.rgb(180, 0, 0) // Dark Red
            )
            QuickFillTool.DATE -> TextAnnotation(
                text = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
                xRatio = xRatio.coerceIn(0f, 0.95f),
                yRatio = yRatio.coerceIn(0f, 0.95f),
                fontSize = 14f,
                textColor = AndroidColor.BLACK
            )
            QuickFillTool.SIGNATURE -> null
        }

        if (textItem != null) {
            pageModifications[currentPageIndex] = currentMod.copy(
                textAnnotations = currentMod.textAnnotations + textItem
            )
        }
    }

    // Text Input Dialog
    if (showTextDialog) {
        var tempText by remember { mutableStateOf(textToPlace) }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Text to Place") },
            text = {
                OutlinedTextField(
                    value = tempText,
                    onValueChange = { tempText = it },
                    label = { Text("Enter text (Name, Answer, Address)") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    textToPlace = tempText
                    showTextDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Quick Signature Drawing Dialog
    if (showSignDialog) {
        var signaturePoints by remember { mutableStateOf<List<DrawingPoint>>(emptyList()) }
        AlertDialog(
            onDismissRequest = { showSignDialog = false },
            title = { Text("Draw Signature") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    signaturePoints = signaturePoints + DrawingPoint(offset.x / size.width, offset.y / size.height)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    signaturePoints = signaturePoints + DrawingPoint(change.position.x / size.width, change.position.y / size.height)
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (signaturePoints.size >= 2) {
                            for (i in 1 until signaturePoints.size) {
                                val p1 = signaturePoints[i - 1]
                                val p2 = signaturePoints[i]
                                drawLine(
                                    color = Color.Black,
                                    start = Offset(p1.x * size.width, p1.y * size.height),
                                    end = Offset(p2.x * size.width, p2.y * size.height),
                                    strokeWidth = 4f
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (signaturePoints.isNotEmpty()) {
                            savedSignaturePoints = signaturePoints
                            val currentMod = pageModifications[currentPageIndex] ?: PageModification(pageIndex = currentPageIndex)
                            val localizedPoints = scaleSignatureToPage(signaturePoints, 0.5f, 0.7f)
                            val drawing = DrawingPath(
                                points = localizedPoints,
                                color = AndroidColor.BLACK,
                                strokeWidth = 3f
                            )
                            pageModifications[currentPageIndex] = currentMod.copy(
                                drawings = currentMod.drawings + drawing
                            )
                        }
                        showSignDialog = false
                    }
                ) {
                    Text("Place Signature")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quick_fill_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    if (selectedPdfUri != null) {
                        val currentMod = pageModifications[currentPageIndex]
                        if (currentMod != null && (currentMod.drawings.isNotEmpty() || currentMod.textAnnotations.isNotEmpty())) {
                            IconButton(
                                onClick = {
                                    if (currentMod.drawings.isNotEmpty()) {
                                        pageModifications[currentPageIndex] = currentMod.copy(
                                            drawings = currentMod.drawings.dropLast(1)
                                        )
                                    } else if (currentMod.textAnnotations.isNotEmpty()) {
                                        pageModifications[currentPageIndex] = currentMod.copy(
                                            textAnnotations = currentMod.textAnnotations.dropLast(1)
                                        )
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo")
                            }
                        }
                        Button(
                            onClick = {
                                val suggestedName = FileUtil.generateSuggestedName(selectedPdfUri, "filled")
                                saveFileLauncher.launch(suggestedName)
                            },
                            enabled = !isSaving
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.quick_fill_action_save))
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (selectedPdfUri == null) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.select_pdf))
                    }
                }
            } else {
                // Tool Selector Toolbar
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = selectedTool == QuickFillTool.TEXT,
                            onClick = {
                                selectedTool = QuickFillTool.TEXT
                                showTextDialog = true
                            },
                            label = { Text("T Text") },
                            leadingIcon = { Icon(Icons.Rounded.TextFields, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = selectedTool == QuickFillTool.CHECKMARK,
                            onClick = { selectedTool = QuickFillTool.CHECKMARK },
                            label = { Text("✓") }
                        )
                        FilterChip(
                            selected = selectedTool == QuickFillTool.CROSS,
                            onClick = { selectedTool = QuickFillTool.CROSS },
                            label = { Text("✗") }
                        )
                        FilterChip(
                            selected = selectedTool == QuickFillTool.DATE,
                            onClick = { selectedTool = QuickFillTool.DATE },
                            label = { Text("📅") }
                        )
                        FilterChip(
                            selected = selectedTool == QuickFillTool.SIGNATURE,
                            onClick = {
                                selectedTool = QuickFillTool.SIGNATURE
                                showSignDialog = true
                            },
                            label = { Text("✍️ Sign") }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.quick_fill_tap_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )

                // Interactive Document Canvas
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = currentPageBitmap
                    if (isRenderingPage || bmp == null) {
                        CircularProgressIndicator()
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .background(Color.White)
                                .onSizeChanged { canvasSize = it }
                                .pointerInput(currentPageIndex, selectedTool, textToPlace) {
                                    detectTapGestures { offset ->
                                        if (size.width > 0 && size.height > 0) {
                                            val xRatio = offset.x / size.width
                                            val yRatio = offset.y / size.height
                                            addAnnotationAt(xRatio, yRatio)
                                        }
                                    }
                                }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "PDF Page",
                                modifier = Modifier.fillMaxSize()
                            )

                            // Render Placed Text Items & Drawings
                            val currentMod = pageModifications[currentPageIndex]
                            if (currentMod != null && canvasSize.width > 0 && canvasSize.height > 0) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    // Drawings
                                    for (drawing in currentMod.drawings) {
                                        if (drawing.points.size >= 2) {
                                            for (i in 1 until drawing.points.size) {
                                                val p1 = drawing.points[i - 1]
                                                val p2 = drawing.points[i]
                                                drawLine(
                                                    color = Color.Black,
                                                    start = Offset(p1.x * size.width, p1.y * size.height),
                                                    end = Offset(p2.x * size.width, p2.y * size.height),
                                                    strokeWidth = drawing.strokeWidth
                                                )
                                            }
                                        }
                                    }
                                }

                                // Overlay Text Items as interactive chips
                                for (annot in currentMod.textAnnotations) {
                                    val leftOffset = (annot.xRatio * canvasSize.width).dp / (LocalContext.current.resources.displayMetrics.density)
                                    val topOffset = (annot.yRatio * canvasSize.height).dp / (LocalContext.current.resources.displayMetrics.density)

                                    Surface(
                                        modifier = Modifier
                                            .offset(x = leftOffset, y = topOffset)
                                            .clickable {
                                                // Remove on tap
                                                pageModifications[currentPageIndex] = currentMod.copy(
                                                    textAnnotations = currentMod.textAnnotations.filter { it.id != annot.id }
                                                )
                                            },
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(annot.textColor).copy(alpha = 0.1f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(annot.textColor).copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = annot.text,
                                                fontSize = annot.fontSize.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(annot.textColor)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(Icons.Rounded.Close, contentDescription = "Delete", modifier = Modifier.size(12.dp), tint = Color.Red)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Page Navigation Bar
                if (totalPages > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (currentPageIndex > 0) currentPageIndex-- },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Page")
                        }

                        Text("Page ${currentPageIndex + 1} of $totalPages", fontWeight = FontWeight.Bold)

                        IconButton(
                            onClick = { if (currentPageIndex < totalPages - 1) currentPageIndex++ },
                            enabled = currentPageIndex < totalPages - 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Page")
                        }
                    }
                }
            }
        }
    }
}

private fun scaleSignatureToPage(
    points: List<DrawingPoint>,
    centerX: Float,
    centerY: Float,
    widthRatio: Float = 0.25f,
    heightRatio: Float = 0.08f
): List<DrawingPoint> {
    if (points.isEmpty()) return emptyList()
    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val spanX = (maxX - minX).coerceAtLeast(0.001f)
    val spanY = (maxY - minY).coerceAtLeast(0.001f)

    val left = (centerX - widthRatio / 2f).coerceIn(0f, 1f - widthRatio)
    val top = (centerY - heightRatio / 2f).coerceIn(0f, 1f - heightRatio)

    return points.map { p ->
        val normX = (p.x - minX) / spanX
        val normY = (p.y - minY) / spanY
        DrawingPoint(
            x = left + normX * widthRatio,
            y = top + normY * heightRatio
        )
    }
}
