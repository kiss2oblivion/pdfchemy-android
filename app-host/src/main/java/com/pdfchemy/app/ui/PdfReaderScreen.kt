package com.pdfchemy.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.Screen
import com.pdfchemy.app.logic.BoundedPageBitmapCache
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.ReaderPageKey
import com.pdfchemy.app.logic.ReaderRenderGate
import com.pdfchemy.app.logic.ReaderZoomCalculator
import com.pdfchemy.app.logic.VanguardThreatResult
import com.pdfchemy.app.sandbox.NativeRendererCoordinator
import com.pdfchemy.app.utils.DocumentStager
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    viewModel: MainViewModel,
    initialUri: Uri?,
    onBack: () -> Unit,
    onNavigateToTool: (Screen) -> Unit
) {
    val sanitizedInitial = initialUri?.takeIf { it != Uri.EMPTY } ?: viewModel.continuityDocumentUri.collectAsState().value?.takeIf { it != Uri.EMPTY }
    var documentUri by remember(sanitizedInitial) { mutableStateOf(sanitizedInitial) }
    val picker = rememberVanguardPdfPicker(
        onNavigateToUnlock = { onNavigateToTool(Screen.UnlockPdf(it)) },
        onPdfSelected = { pickedUri ->
            viewModel.readerNavigation.onEnterReader(pickedUri)
            documentUri = pickedUri
        }
    )
    val activeUri = documentUri?.takeIf { it != Uri.EMPTY }
    if (activeUri != null) {
        PdfReaderDocument(
            viewModel = viewModel,
            initialUri = activeUri,
            onBack = onBack,
            onNavigateToTool = onNavigateToTool
        )
    } else {
        BackHandler { onBack() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.select_pdf_for_reading)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                    Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.select_pdf_for_reading))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfReaderDocument(
    viewModel: MainViewModel,
    initialUri: Uri,
    onBack: () -> Unit,
    onNavigateToTool: (Screen) -> Unit
) {
    SecureScreenContent()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var selectedPdfUri by remember(initialUri) { mutableStateOf<Uri?>(initialUri) }
    var activeSessionLease by remember { mutableStateOf<java.io.Closeable?>(null) }
    var totalPages by remember { mutableIntStateOf(0) }
    var fileName by remember { mutableStateOf("") }
    
    // Zoom, Pan, and Bounded Bitmap Cache state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var fitPage by remember { mutableStateOf(false) }
    var activePointerCount by remember { mutableIntStateOf(1) }
    val listState = rememberLazyListState()
    val bitmapCache = remember(initialUri) { BoundedPageBitmapCache(maxEntries = 6, maxBytes = 32L * 1024L * 1024L) }
    val renderGate = remember(initialUri) { ReaderRenderGate() }
    
    // Command Centre state
    var showControls by remember { mutableStateOf(true) }
    
    // Search and TOC state
    val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    var bookmarks by remember { mutableStateOf<List<com.pdfchemy.app.logic.OutlineBookmark>>(emptyList()) }
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<com.pdfchemy.app.logic.TextMatchOccurrence>>(emptyList()) }
    var currentMatchIndex by remember { mutableStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }
    
    // Vanguard Threat state
    val isVanguardEnabled by viewModel.isVanguardEnabled.collectAsState()
    var showVanguardBlockedDialog by remember { mutableStateOf(false) }
    var showVanguardEncryptedDialog by remember { mutableStateOf(false) }
    var showVanguardDamagedDialog by remember { mutableStateOf(false) }
    var isVanguardScanning by remember { mutableStateOf(false) }
    
    BackHandler {
        if (ReaderZoomCalculator.isZoomed(scale)) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        } else {
            onBack()
        }
    }
    
    val isRememberPositionEnabled by viewModel.isRememberPositionEnabled.collectAsState()

    DisposableEffect(initialUri) {
        onDispose {
            renderGate.close()
            bitmapCache.clear()
            activeSessionLease?.close()
            activeSessionLease = null
            selectedPdfUri?.let { uri ->
                if (viewModel.continuityDocumentUri.value != uri && viewModel.readerNavigation.readerReturnUri != uri) {
                    DocumentStager.release(uri)
                }
            }
        }
    }
    
    LaunchedEffect(initialUri) {
        guardDocumentLoad(onFailure = {
            showVanguardBlockedDialog = true
            isVanguardScanning = false
        }) {
            val staged = withContext(Dispatchers.IO) { DocumentStager.stageDocumentCancellable(context, initialUri) }
            val newLease = DocumentStager.retain(staged)
            val oldLease = activeSessionLease
            activeSessionLease = newLease
            oldLease?.close()
            val stagedUri = staged.uri
            selectedPdfUri = stagedUri
            viewModel.readerNavigation.onEnterReader(stagedUri)
            fileName = FileUtils.getFileName(context, stagedUri) ?: "Document.pdf"
            
            if (isVanguardEnabled) {
                isVanguardScanning = true
                try {
                    val threat = com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, stagedUri)
                    when (threat) {
                        is VanguardThreatResult.Clean -> {
                            totalPages = PdfEditor.getPageCount(context, stagedUri)
                        }
                        is VanguardThreatResult.EncryptedCannotVerify -> {
                            showVanguardEncryptedDialog = true
                        }
                        is VanguardThreatResult.ExecutableThreat -> {
                            showVanguardBlockedDialog = true
                        }
                        is VanguardThreatResult.ParseFailed -> {
                            showVanguardDamagedDialog = true
                        }
                    }
                } finally {
                    isVanguardScanning = false
                }
            } else {
                totalPages = PdfEditor.getPageCount(context, stagedUri)
            }

            if (totalPages > 0) {
                try {
                    bookmarks = com.pdfchemy.app.logic.PdfOutlineReader.extractOutline(context, stagedUri)
                } catch (_: Exception) {
                }
            }
            
            if (isRememberPositionEnabled && totalPages > 0) {
                val docId = com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, initialUri)
                val prefs = context.getSharedPreferences("reader_prefs", android.content.Context.MODE_PRIVATE)
                val savedPage = prefs.getInt("page_$docId", 0)
                if (savedPage in 0 until totalPages) {
                    listState.scrollToItem(savedPage)
                }
            }
        }
    }
    
    // Save position continuously
    LaunchedEffect(listState.firstVisibleItemIndex, isRememberPositionEnabled) {
        if (isRememberPositionEnabled && totalPages > 0) {
            val docId = com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, initialUri)
            val prefs = context.getSharedPreferences("reader_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().putInt("page_$docId", listState.firstVisibleItemIndex).apply()
        }
    }

    if (showVanguardBlockedDialog || showVanguardDamagedDialog || showVanguardEncryptedDialog) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.vanguard_blocked_title)) },
            text = { Text("This document cannot be read safely.") },
            confirmButton = { Button(onClick = onBack) { Text(stringResource(R.string.ok)) } }
        )
        return
    }

    if (isVanguardScanning) {
        VanguardScanningOverlay(visible = true, fileName = fileName)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE5E5E5)) // Neutral reading background
    ) {
        if (totalPages > 0 && selectedPdfUri != null) {
            // Interactive Reader Surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = { tapOffset ->
                                val result = ReaderZoomCalculator.toggleDoubleTapZoom(
                                    currentScale = scale,
                                    tapX = tapOffset.x,
                                    tapY = tapOffset.y,
                                    viewportWidth = size.width.toFloat(),
                                    viewportHeight = size.height.toFloat()
                                )
                                scale = result.scale
                                offsetX = result.offsetX
                                offsetY = result.offsetY
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressedCount = event.changes.count { it.pressed }
                                activePointerCount = pressedCount.coerceAtLeast(1)
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val shouldHandleTransform = pressedCount >= 2 ||
                                    ReaderZoomCalculator.isZoomed(scale) ||
                                    kotlin.math.abs(zoomChange - 1f) > 0.01f
                                if (shouldHandleTransform) {
                                    val result = ReaderZoomCalculator.applyGesture(
                                        currentScale = scale,
                                        currentOffsetX = offsetX,
                                        currentOffsetY = offsetY,
                                        zoomChange = zoomChange,
                                        panX = panChange.x,
                                        panY = panChange.y,
                                        viewportWidth = size.width.toFloat(),
                                        viewportHeight = size.height.toFloat()
                                    )
                                    scale = result.scale
                                    offsetX = result.offsetX
                                    offsetY = result.offsetY
                                    if (result.verticalScrollDelta != 0f) {
                                        coroutineScope.launch {
                                            listState.scrollBy(result.verticalScrollDelta)
                                        }
                                    }
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) {
                                            change.consume()
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            activePointerCount = 1
                        }
                    }
            ) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = ReaderZoomCalculator.isNativeScrollEnabled(scale, activePointerCount),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 120.dp, start = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                ) {
                    items(totalPages, key = { it }) { index ->
                        PdfReaderPageItem(
                            context = context,
                            uri = selectedPdfUri!!,
                            pageIndex = index,
                            fitPage = fitPage,
                            bitmapCache = bitmapCache,
                            renderGate = renderGate
                        )
                    }
                }
            }
        }

        // Top AppBar Command Centre
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
            TopAppBar(
                title = { 
                    Text(
                        text = fileName, 
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        fitPage = !fitPage
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }) {
                        Icon(
                            imageVector = if (fitPage) Icons.Rounded.FitScreen else Icons.Rounded.Fullscreen,
                            contentDescription = if (fitPage) "Fit Width" else "Fit Page"
                        )
                    }
                    IconButton(onClick = { isSearchMode = !isSearchMode }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search")
                    }
                    if (bookmarks.isNotEmpty()) {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Table of Contents")
                        }
                    }
                    IconButton(onClick = { onNavigateToTool(Screen.PdfEditor(initialPdfUri = selectedPdfUri)) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            )
            AnimatedVisibility(visible = isSearchMode) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search document...") },
                            singleLine = true,
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = ""; searchMatches = emptyList() }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                                if (searchQuery.isNotBlank() && initialUri != null) {
                                    isSearching = true
                                    coroutineScope.launch {
                                        try {
                                            val summary = com.pdfchemy.app.logic.PdfFindAndReplaceEngine.findOccurrences(context, initialUri, searchQuery)
                                            searchMatches = summary.occurrences
                                            currentMatchIndex = 0
                                            if (searchMatches.isNotEmpty()) {
                                                listState.animateScrollToItem(searchMatches[0].pageIndex)
                                            }
                                        } finally {
                                            isSearching = false
                                        }
                                    }
                                }
                            })
                        )
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.padding(start = 12.dp).size(24.dp))
                        } else if (searchMatches.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${currentMatchIndex + 1}/${searchMatches.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                                IconButton(onClick = {
                                    val newIdx = if (currentMatchIndex > 0) currentMatchIndex - 1 else searchMatches.size - 1
                                    currentMatchIndex = newIdx
                                    coroutineScope.launch { listState.animateScrollToItem(searchMatches[newIdx].pageIndex) }
                                }) { Icon(Icons.Rounded.KeyboardArrowUp, null) }
                                IconButton(onClick = {
                                    val newIdx = if (currentMatchIndex < searchMatches.size - 1) currentMatchIndex + 1 else 0
                                    currentMatchIndex = newIdx
                                    coroutineScope.launch { listState.animateScrollToItem(searchMatches[newIdx].pageIndex) }
                                }) { Icon(Icons.Rounded.KeyboardArrowDown, null) }
                            }
                        }
                    }
                }
            }
            }
        }

        // Bottom AppBar Command Centre (Document Continuity Tools)
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Page indicator
                    val firstVisible = listState.firstVisibleItemIndex
                    Text(
                        text = "${firstVisible + 1} of $totalPages",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ContinuityToolButton(
                            icon = Icons.Rounded.Compress, 
                            label = stringResource(R.string.menu_compress), 
                            onClick = { 
                                viewModel.setContinuityUri(selectedPdfUri)
                                onNavigateToTool(Screen.CompressPdf) 
                            }
                        )
                        ContinuityToolButton(
                            icon = Icons.Rounded.DocumentScanner, 
                            label = stringResource(R.string.menu_ocr_pdf), 
                            onClick = { 
                                viewModel.setContinuityUri(selectedPdfUri)
                                onNavigateToTool(Screen.OcrPdf) 
                            }
                        )
                        ContinuityToolButton(
                            icon = Icons.Rounded.Draw, 
                            label = stringResource(R.string.menu_sign_pdf), 
                            onClick = { 
                                viewModel.setContinuityUri(selectedPdfUri)
                                onNavigateToTool(Screen.SignPdf) 
                            }
                        )
                        ContinuityToolButton(
                            icon = Icons.Rounded.Security, 
                            label = stringResource(R.string.menu_protect_pdf), 
                            onClick = { 
                                viewModel.setContinuityUri(selectedPdfUri)
                                onNavigateToTool(Screen.ProtectPdf) 
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContinuityToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun PdfReaderPageItem(
    context: Context,
    uri: Uri,
    pageIndex: Int,
    fitPage: Boolean = false,
    bitmapCache: BoundedPageBitmapCache,
    renderGate: ReaderRenderGate
) {
    val targetWidth = remember(context, fitPage) {
        val baseWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(360)
        if (fitPage) baseWidth else (baseWidth * 1.35f).toInt()
    }
    val pageKey = remember(uri, pageIndex, targetWidth, fitPage) {
        ReaderPageKey(uri.toString(), pageIndex, targetWidth, fitPage)
    }
    var bitmap by remember(pageKey) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(pageKey) {
        val cached = bitmapCache.acquire(pageKey)
        if (cached != null) {
            bitmap = cached
        }
        onDispose {
            renderGate.cancelPage(pageIndex)
            bitmapCache.release(bitmap)
            bitmap = null
        }
    }

    LaunchedEffect(pageKey) {
        if (bitmap != null && !bitmap!!.isRecycled) return@LaunchedEffect
        val token = renderGate.beginRender(pageIndex)
        if (token < 0L) return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            if (!renderGate.isCurrent(pageIndex, token)) return@withContext null
            NativeRendererCoordinator.renderUriToBitmap(context, uri, pageIndex, targetWidth)
        }
        if (rendered != null) {
            if (renderGate.isCurrent(pageIndex, token)) {
                bitmap = bitmapCache.putAndAcquire(pageKey, rendered)
            } else if (!rendered.isRecycled) {
                rendered.recycle()
            }
        }
    }

    val maxPageHeightDp = with(LocalDensity.current) {
        (context.resources.displayMetrics.heightPixels * 0.82f).toDp()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (fitPage) Modifier.heightIn(max = maxPageHeightDp) else Modifier.wrapContentHeight())
            .shadow(4.dp, RoundedCornerShape(4.dp))
            .background(Color.White)
            .clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        val activeBitmap = bitmap?.takeIf { !it.isRecycled }
        if (activeBitmap != null) {
            Image(
                bitmap = activeBitmap.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = if (fitPage) ContentScale.Fit else ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // Placeholder while loading
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        }
    }
}
