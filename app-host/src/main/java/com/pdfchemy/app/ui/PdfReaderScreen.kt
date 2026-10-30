package com.pdfchemy.app.ui

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.Screen
import com.pdfchemy.app.logic.BoundedPageBitmapCache
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.ReaderAccessibilityLayout
import com.pdfchemy.app.logic.ReaderBackAction
import com.pdfchemy.app.logic.ReaderCommandAction
import com.pdfchemy.app.logic.ReaderCommandCentreCatalog
import com.pdfchemy.app.logic.ReaderControlsUiState
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
        key(activeUri) {
            PdfReaderDocument(
                viewModel = viewModel,
                initialUri = activeUri,
                onBack = onBack,
                onNavigateToTool = onNavigateToTool
            )
        }
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
    var fitPage by rememberSaveable { mutableStateOf(false) }
    var activePointerCount by remember { mutableIntStateOf(1) }
    val listState = rememberLazyListState()
    val bitmapCache = remember(initialUri, context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val (maxEntries, maxBytes) = BoundedPageBitmapCache.computeAdaptiveBudget(
            maxMemoryBytes = Runtime.getRuntime().maxMemory(),
            isLowRamDevice = am?.isLowRamDevice == true
        )
        BoundedPageBitmapCache(maxEntries = maxEntries, maxBytes = maxBytes)
    }
    val renderGate = remember(initialUri) { ReaderRenderGate() }

    DisposableEffect(context, bitmapCache) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() {
                bitmapCache.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            }
            override fun onTrimMemory(level: Int) {
                bitmapCache.onTrimMemory(level)
            }
        }
        context.registerComponentCallbacks(callbacks)
        onDispose {
            context.unregisterComponentCallbacks(callbacks)
        }
    }
    
    // Saveable Tap-to-Reveal & Overlay Controls state
    var showControls by rememberSaveable { mutableStateOf(true) }
    var isSearchMode by rememberSaveable { mutableStateOf(false) }
    var showTocSheet by rememberSaveable { mutableStateOf(false) }
    var showCommandCentreSheet by rememberSaveable { mutableStateOf(false) }

    fun applyControlsState(next: ReaderControlsUiState) {
        showControls = next.showControls
        isSearchMode = next.isSearchMode
        showTocSheet = next.showTocSheet
        showCommandCentreSheet = next.showCommandCentreSheet
    }

    fun currentControlsState(): ReaderControlsUiState = ReaderControlsUiState(
        showControls = showControls,
        isSearchMode = isSearchMode,
        showTocSheet = showTocSheet,
        showCommandCentreSheet = showCommandCentreSheet
    )

    // Search and TOC state
    var bookmarks by remember { mutableStateOf<List<com.pdfchemy.app.logic.OutlineBookmark>>(emptyList()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<com.pdfchemy.app.logic.TextMatchOccurrence>>(emptyList()) }
    var currentMatchIndex by rememberSaveable { mutableIntStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }
    var hasExecutedSearch by rememberSaveable { mutableStateOf(false) }

    // Vanguard & Document Access state
    val isVanguardEnabled by viewModel.isVanguardEnabled.collectAsState()
    var showVanguardBlockedDialog by remember { mutableStateOf(false) }
    var showVanguardEncryptedDialog by remember { mutableStateOf(false) }
    var showVanguardDamagedDialog by remember { mutableStateOf(false) }
    var showAccessErrorDialog by remember { mutableStateOf(false) }
    var isVanguardScanning by remember { mutableStateOf(false) }
    var isDocumentLoading by remember(initialUri) { mutableStateOf(true) }
    var isPositionRestored by remember(initialUri) { mutableStateOf(false) }
    val actionsEnabled = ReaderControlsUiState.canExecuteDocumentAction(
        isDocumentLoading = isDocumentLoading,
        isVanguardScanning = isVanguardScanning,
        totalPages = totalPages
    )

    fun launchCommandAction(action: ReaderCommandAction) {
        if (!ReaderControlsUiState.canExecuteDocumentAction(isDocumentLoading, isVanguardScanning, totalPages)) return
        applyControlsState(currentControlsState().onDismissCommandCentre())
        viewModel.setContinuityUri(selectedPdfUri)
        onNavigateToTool(ReaderCommandCentreCatalog.resolveScreen(action, selectedPdfUri))
    }
    
    BackHandler {
        val (action, nextState) = currentControlsState().resolveBackAction(ReaderZoomCalculator.isZoomed(scale))
        applyControlsState(nextState)
        when (action) {
            ReaderBackAction.CLOSE_COMMAND_CENTRE,
            ReaderBackAction.CLOSE_TOC,
            ReaderBackAction.CLOSE_SEARCH -> Unit
            ReaderBackAction.RESET_ZOOM -> {
                scale = 1f
                offsetX = 0f
                offsetY = 0f
            }
            ReaderBackAction.EXIT_READER -> onBack()
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
        isDocumentLoading = true
        guardDocumentLoadWithCause(onFailure = { err ->
            isVanguardScanning = false
            isDocumentLoading = false
            val failureKind = com.pdfchemy.app.utils.DocumentIdentity.classifyLoadException(err)
            if (failureKind == com.pdfchemy.app.utils.DocumentLoadFailureKind.REVOKED_OR_INACCESSIBLE) {
                viewModel.removeHistoryItem(initialUri.toString())
                showAccessErrorDialog = true
            } else {
                showVanguardDamagedDialog = true
            }
        }) {
            try {
                val staged = withContext(Dispatchers.IO) { DocumentStager.stageDocumentCancellable(context, initialUri) }
                val newLease = DocumentStager.retain(staged)
                val oldLease = activeSessionLease
                activeSessionLease = newLease
                oldLease?.close()
                val stagedUri = staged.uri
                selectedPdfUri = stagedUri
                viewModel.clearContinuityUri(stagedUri)
                viewModel.readerNavigation.onEnterReader(stagedUri)
                fileName = FileUtils.getFileName(context, stagedUri) ?: "Document.pdf"

                // Outline, audit, and page-count operations share the single-flight worker.
                // Finish them in sequence before publishing totalPages and enabling Reader actions.
                val startupResult = com.pdfchemy.app.logic.ReaderStartupCoordinator.executeSequentialStartup(
                    isVanguardEnabled = isVanguardEnabled,
                    onVanguardScanningChanged = { isVanguardScanning = it },
                    checkVanguardThreat = { com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, stagedUri) },
                    getPageCount = { PdfEditor.getPageCount(context, stagedUri) },
                    extractOutline = { com.pdfchemy.app.logic.PdfOutlineReader.extractOutline(context, stagedUri) },
                    onRecordHistory = { viewModel.recordReaderHistory(initialUri, fileName) }
                )
                // Read the saved position before the page list is composed and can overwrite it.
                val savedPos = if (isRememberPositionEnabled && startupResult.totalPages > 0) {
                    val docId = com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, stagedUri)
                    com.pdfchemy.app.utils.DocumentIdentity.getSavedReaderPosition(context, docId, startupResult.totalPages)
                } else null
                com.pdfchemy.app.logic.ReaderStartupCoordinator.publishReadyThenRestorePosition(
                    publishReady = {
                        showVanguardBlockedDialog = startupResult.showVanguardBlockedDialog
                        showVanguardEncryptedDialog = startupResult.showVanguardEncryptedDialog
                        showVanguardDamagedDialog = startupResult.showVanguardDamagedDialog
                        bookmarks = startupResult.bookmarks
                        totalPages = startupResult.totalPages
                        isDocumentLoading = false
                    },
                    restorePosition = {
                        // Suspends until the LazyColumn's first layout, which requires the ready state above.
                        savedPos?.let { listState.scrollToItem(it.pageIndex, it.scrollOffsetPx) }
                    },
                    onPositionRestored = { isPositionRestored = true }
                )
            } finally {
                isDocumentLoading = false
            }
        }
    }
    
    // Save position (page index + scroll offset) continuously
    LaunchedEffect(
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        isRememberPositionEnabled,
        totalPages,
        isDocumentLoading,
        isPositionRestored
    ) {
        val activeUri = selectedPdfUri ?: initialUri
        if (!isDocumentLoading && isPositionRestored && isRememberPositionEnabled && totalPages > 0) {
            val docId = com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, activeUri)
            com.pdfchemy.app.utils.DocumentIdentity.saveReaderPosition(
                context = context,
                docId = docId,
                pageIndex = listState.firstVisibleItemIndex,
                scrollOffsetPx = listState.firstVisibleItemScrollOffset
            )
        }
    }

    if (showAccessErrorDialog) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.vanguard_provider_error_title)) },
            text = { Text(stringResource(R.string.vanguard_provider_error_message)) },
            confirmButton = { Button(onClick = onBack) { Text(stringResource(R.string.ok)) } }
        )
        return
    }

    if (showVanguardEncryptedDialog) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.vanguard_encrypted_title)) },
            text = { Text(stringResource(R.string.vanguard_encrypted_message)) },
            confirmButton = {
                Button(onClick = { onNavigateToTool(Screen.UnlockPdf(selectedPdfUri ?: initialUri)) }) {
                    Text(stringResource(R.string.vanguard_action_unlock))
                }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
            }
        )
        return
    }

    if (showVanguardDamagedDialog) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.vanguard_damaged_title)) },
            text = { Text(stringResource(R.string.vanguard_damaged_message)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.setContinuityUri(selectedPdfUri ?: initialUri)
                    onNavigateToTool(Screen.RepairPdf)
                }) {
                    Text(stringResource(R.string.menu_repair_pdf))
                }
            },
            dismissButton = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
            }
        )
        return
    }

    if (showVanguardBlockedDialog) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.vanguard_blocked_title)) },
            text = { Text(stringResource(R.string.vanguard_blocked_message)) },
            confirmButton = { Button(onClick = onBack) { Text(stringResource(R.string.ok)) } }
        )
        return
    }

    if (isVanguardScanning) {
        VanguardScanningOverlay(visible = true, fileName = fileName)
        return
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val stablePadding = remember(isLandscape) {
        ReaderControlsUiState.computeStableViewportPadding(isLandscape)
    }
    val barLayout = remember(configuration.screenWidthDp, isLandscape, configuration.fontScale) {
        ReaderAccessibilityLayout.computeBarLayout(
            screenWidthDp = configuration.screenWidthDp,
            isLandscape = isLandscape,
            fontScale = configuration.fontScale
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE5E5E5)) // Neutral reading background
    ) {
        if (isDocumentLoading || totalPages == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        } else if (totalPages > 0 && selectedPdfUri != null) {
            // Interactive Reader Surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { applyControlsState(currentControlsState().onCanvasTap()) },
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
                    contentPadding = PaddingValues(
                        top = stablePadding.topDp.dp,
                        bottom = stablePadding.bottomDp.dp,
                        start = stablePadding.horizontalDp.dp,
                        end = stablePadding.horizontalDp.dp
                    ),
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

        // Non-intrusive floating page badge when controls are hidden in reading mode
        val currentPageOneBased = listState.firstVisibleItemIndex + 1
        val fullPageIndicatorText = stringResource(R.string.reader_page_indicator, currentPageOneBased, totalPages)
        AnimatedVisibility(
            visible = !showControls && !isDocumentLoading && totalPages > 0,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = fullPageIndicatorText
                        role = Role.Button
                    }
                    .clickable { applyControlsState(currentControlsState().onCanvasTap()) }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        text = stringResource(R.string.reader_page_badge_compact, currentPageOneBased, totalPages),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }

        // Quick zoom reset pill when zoomed in
        val resetZoomLabel = stringResource(R.string.reader_reset_zoom_fmt, scale)
        AnimatedVisibility(
            visible = ReaderZoomCalculator.isZoomed(scale),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = if (showControls) 132.dp else 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = resetZoomLabel
                        role = Role.Button
                    }
                    .clickable {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(
                        text = resetZoomLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1
                    )
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            fitPage = !fitPage
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                        enabled = actionsEnabled
                    ) {
                        Icon(
                            imageVector = if (fitPage) Icons.Rounded.FitScreen else Icons.Rounded.Fullscreen,
                            contentDescription = stringResource(
                                if (fitPage) R.string.reader_fit_width else R.string.reader_fit_page
                            )
                        )
                    }
                    IconButton(
                        onClick = { applyControlsState(currentControlsState().onToggleSearch()) },
                        enabled = actionsEnabled
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.reader_action_search))
                    }
                    if (bookmarks.isNotEmpty()) {
                        IconButton(
                            onClick = { applyControlsState(currentControlsState().onOpenToc()) },
                            enabled = actionsEnabled
                        ) {
                            Icon(Icons.Rounded.Menu, contentDescription = stringResource(R.string.reader_toc_title))
                        }
                    }
                    IconButton(
                        onClick = { launchCommandAction(ReaderCommandAction.REFLOW_READER) },
                        enabled = actionsEnabled
                    ) {
                        Icon(Icons.Rounded.MenuBook, contentDescription = stringResource(R.string.menu_reflow_reader))
                    }
                    IconButton(
                        onClick = { launchCommandAction(ReaderCommandAction.EDIT_ANNOTATE) },
                        enabled = actionsEnabled
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.menu_edit_pdf))
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    hasExecutedSearch = false
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text(stringResource(R.string.reader_search_hint)) },
                                singleLine = true,
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = {
                                            searchQuery = ""
                                            searchMatches = emptyList()
                                            hasExecutedSearch = false
                                        }) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                contentDescription = stringResource(R.string.reader_action_clear_search)
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                                    val targetSearchUri = selectedPdfUri ?: initialUri
                                    if (searchQuery.isNotBlank()) {
                                        isSearching = true
                                        coroutineScope.launch {
                                            try {
                                                val summary = com.pdfchemy.app.logic.PdfFindAndReplaceEngine.findOccurrences(context, targetSearchUri, searchQuery)
                                                searchMatches = summary.occurrences
                                                hasExecutedSearch = true
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
                                    }) {
                                        Icon(
                                            Icons.Rounded.KeyboardArrowUp,
                                            contentDescription = stringResource(R.string.previous_match)
                                        )
                                    }
                                    IconButton(onClick = {
                                        val newIdx = if (currentMatchIndex < searchMatches.size - 1) currentMatchIndex + 1 else 0
                                        currentMatchIndex = newIdx
                                        coroutineScope.launch { listState.animateScrollToItem(searchMatches[newIdx].pageIndex) }
                                    }) {
                                        Icon(
                                            Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.next_match)
                                        )
                                    }
                                }
                            }
                        }
                        if (!isSearching && hasExecutedSearch && searchQuery.isNotBlank() && searchMatches.isEmpty()) {
                            Text(
                                text = stringResource(R.string.reader_search_no_matches),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                            )
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
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(
                            horizontal = barLayout.horizontalPaddingDp.dp,
                            vertical = barLayout.verticalPaddingDp.dp
                        )
                ) {
                    Text(
                        text = fullPageIndicatorText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = if (barLayout.compactLandscape) 4.dp else 8.dp)
                    )
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ContinuityToolButton(
                            icon = Icons.Rounded.Compress, 
                            label = stringResource(R.string.menu_compress), 
                            enabled = actionsEnabled,
                            onClick = { launchCommandAction(ReaderCommandAction.COMPRESS) }
                        )
                        ContinuityToolButton(
                            icon = Icons.Rounded.DocumentScanner, 
                            label = stringResource(R.string.menu_ocr_pdf), 
                            enabled = actionsEnabled,
                            onClick = { launchCommandAction(ReaderCommandAction.OCR_SEARCHABLE) }
                        )
                        ContinuityToolButton(
                            icon = Icons.Rounded.Draw, 
                            label = stringResource(R.string.menu_sign_pdf), 
                            enabled = actionsEnabled,
                            onClick = { launchCommandAction(ReaderCommandAction.SIGN_PDF) }
                        )
                        ContinuityToolButton(
                            icon = Icons.Rounded.Security, 
                            label = stringResource(R.string.menu_protect_pdf), 
                            enabled = actionsEnabled,
                            onClick = { launchCommandAction(ReaderCommandAction.PROTECT_ENCRYPT) }
                        )
                        ContinuityToolButton(
                            icon = Icons.Rounded.Apps,
                            label = stringResource(R.string.menu_more_tools),
                            enabled = actionsEnabled,
                            onClick = { applyControlsState(currentControlsState().onOpenCommandCentre()) }
                        )
                    }
                }
            }
        }

        if (showCommandCentreSheet) {
            ModalBottomSheet(
                onDismissRequest = { applyControlsState(currentControlsState().onDismissCommandCentre()) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.menu_more_tools),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                    ) {
                        items(ReaderCommandCentreCatalog.allSheetActions) { action ->
                            val (icon, titleRes) = readerCommandPresentation(action)
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = stringResource(titleRes),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable(enabled = actionsEnabled) { launchCommandAction(action) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        if (showTocSheet && bookmarks.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = { applyControlsState(currentControlsState().onDismissToc()) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.reader_toc_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(bookmarks) { bookmark ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = bookmark.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                supportingContent = {
                                    Text(stringResource(R.string.reader_page_content_desc, bookmark.pageNumber.coerceAtLeast(1)))
                                },
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable {
                                        applyControlsState(currentControlsState().onDismissToc())
                                        val targetPage = (bookmark.pageNumber - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                                        coroutineScope.launch {
                                            listState.scrollToItem(targetPage)
                                        }
                                    }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

private fun readerCommandPresentation(
    action: ReaderCommandAction
): Pair<androidx.compose.ui.graphics.vector.ImageVector, Int> {
    return when (action) {
        ReaderCommandAction.EDIT_ANNOTATE -> Icons.Rounded.Edit to R.string.menu_edit_pdf
        ReaderCommandAction.REFLOW_READER -> Icons.Rounded.MenuBook to R.string.menu_reflow_reader
        ReaderCommandAction.COMPRESS -> Icons.Rounded.Compress to R.string.menu_compress
        ReaderCommandAction.OCR_SEARCHABLE -> Icons.Rounded.DocumentScanner to R.string.menu_ocr_pdf
        ReaderCommandAction.SIGN_PDF -> Icons.Rounded.Draw to R.string.menu_sign_pdf
        ReaderCommandAction.PROTECT_ENCRYPT -> Icons.Rounded.Security to R.string.menu_protect_pdf
        ReaderCommandAction.SPLIT -> Icons.Rounded.CallSplit to R.string.menu_split
        ReaderCommandAction.PAGE_ORGANIZER -> Icons.Rounded.ViewModule to R.string.menu_page_organizer
        ReaderCommandAction.ROTATE_PAGES -> Icons.Rounded.RotateRight to R.string.menu_rotate_pages
        ReaderCommandAction.DELETE_PAGES -> Icons.Rounded.Delete to R.string.menu_delete_pages
        ReaderCommandAction.FILL_FORM -> Icons.Rounded.DynamicForm to R.string.menu_fill_form
        ReaderCommandAction.QUICK_FILL_SIGN -> Icons.Rounded.Draw to R.string.menu_quick_fill_sign
        ReaderCommandAction.EXTRACT_TABLES -> Icons.Rounded.TableChart to R.string.table_extractor_title
        ReaderCommandAction.EXPORT_OFFICE -> Icons.Rounded.Description to R.string.title_office_exporter
        ReaderCommandAction.REDACT -> Icons.Rounded.VisibilityOff to R.string.menu_redaction_studio
        ReaderCommandAction.WATERMARK -> Icons.Rounded.BrandingWatermark to R.string.menu_watermark_pdf
        ReaderCommandAction.PAGE_NUMBERS -> Icons.Rounded.Numbers to R.string.menu_page_number_pdf
    }
}

@Composable
fun ContinuityToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .defaultMinSize(
                minWidth = ReaderAccessibilityLayout.MIN_TOUCH_TARGET_DP.dp,
                minHeight = ReaderAccessibilityLayout.MIN_TOUCH_TARGET_DP.dp
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
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
    var renderFailed by remember(pageKey) { mutableStateOf(false) }
    var retryAttempt by remember(pageKey) { mutableIntStateOf(0) }

    DisposableEffect(pageKey) {
        val cached = bitmapCache.acquire(pageKey)
        if (cached != null) {
            bitmap = cached
            renderFailed = false
        }
        onDispose {
            renderGate.cancelPage(pageIndex)
            bitmapCache.release(bitmap)
            bitmap = null
        }
    }

    LaunchedEffect(pageKey, retryAttempt) {
        if (bitmap != null && !bitmap!!.isRecycled) return@LaunchedEffect
        renderFailed = false
        val token = renderGate.beginRender(pageIndex)
        if (token < 0L) return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            if (!renderGate.isCurrent(pageIndex, token)) return@withContext null
            try {
                NativeRendererCoordinator.renderUriToBitmap(context, uri, pageIndex, targetWidth)
            } catch (_: Exception) {
                null
            }
        }
        if (rendered != null) {
            if (renderGate.isCurrent(pageIndex, token)) {
                bitmap = bitmapCache.putAndAcquire(pageKey, rendered)
                renderFailed = false
            } else if (!rendered.isRecycled) {
                rendered.recycle()
            }
        } else if (renderGate.isCurrent(pageIndex, token)) {
            renderFailed = true
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
                contentDescription = stringResource(R.string.reader_page_content_desc, pageIndex + 1),
                contentScale = if (fitPage) ContentScale.Fit else ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (renderFailed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .background(Color.White)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.page_render_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { retryAttempt++ }) {
                    Text(stringResource(R.string.retry))
                }
            }
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
