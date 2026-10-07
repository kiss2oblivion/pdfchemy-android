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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.Screen
import com.pdfchemy.app.logic.PdfEditor
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
    var documentUri by remember(initialUri) { mutableStateOf(initialUri) }
    val picker = rememberVanguardPdfPicker(
        onNavigateToUnlock = { onNavigateToTool(Screen.UnlockPdf(it)) },
        onPdfSelected = { documentUri = it }
    )
    val uri = documentUri
    if (uri != null) {
        PdfReaderDocument(viewModel, uri, onBack, onNavigateToTool)
    } else {
        BackHandler { onBack() }
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.select_pdf_for_reading)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.desc_back))
                    }
                }
            )
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Button(onClick = { picker.launch() }) {
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
    
    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var totalPages by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadFailure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var documentId by remember { mutableStateOf("") }
    var showNavigation by remember { mutableStateOf(false) }
    var jumpInput by remember { mutableStateOf("") }
    var fitPage by remember { mutableStateOf(false) }
    var personalBookmarks by remember { mutableStateOf<Set<Int>>(emptySet()) }
    viewModel.readerSourceUri = initialUri
    var fileName by remember { mutableStateOf("") }
    
    // Zoom and Pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    
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
    val searchGeneration = remember { com.pdfchemy.app.logic.LatestRequest() }
    
    // Vanguard Threat state
    val isVanguardEnabled by viewModel.isVanguardEnabled.collectAsState()
    var showVanguardBlockedDialog by remember { mutableStateOf(false) }
    var showVanguardEncryptedDialog by remember { mutableStateOf(false) }
    var showVanguardDamagedDialog by remember { mutableStateOf(false) }
    var isVanguardScanning by remember { mutableStateOf(false) }
    
    BackHandler {
        if (scale > 1f) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        } else {
            onBack()
        }
    }
    
    val isRememberPositionEnabled by viewModel.isRememberPositionEnabled.collectAsState()
    
    LaunchedEffect(initialUri, retry) {
        loading = true
        loadFailure = null
        guardDocumentLoad(onFailure = {
            loadFailure = "Cannot open this document. Check its access permission or select it again."
            loading = false
            isVanguardScanning = false
        }) {
            val docId = withContext(Dispatchers.IO) { com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, initialUri) }
            val stagedUri = withContext(Dispatchers.IO) { DocumentStager.stageDocumentCancellable(context, initialUri).uri }
            selectedPdfUri = stagedUri
            documentId = docId
            personalBookmarks = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                .getStringSet("bookmarks_$docId", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()
            
            // Fetch bookmarks asynchronously
            coroutineScope.launch {
                try {
                    bookmarks = com.pdfchemy.app.logic.PdfOutlineReader.extractOutline(context, initialUri)
                } catch (e: Exception) {
                    // Ignore
                }
            }
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
            
            loading = false
            if (totalPages == 0 && !showVanguardBlockedDialog && !showVanguardEncryptedDialog && !showVanguardDamagedDialog) {
                loadFailure = "No readable pages were found. Try Repair PDF or select another document."
            }
            if (totalPages > 0) {
                val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
                val sessionPosition = viewModel.readerPositions[initialUri.toString()]
                val savedPage = sessionPosition?.page ?: if (isRememberPositionEnabled) prefs.getInt("page_$docId", 0) else 0
                val offset = sessionPosition?.offset ?: if (isRememberPositionEnabled) prefs.getInt("offset_$docId", 0) else 0
                listState.scrollToItem(savedPage.coerceIn(0, totalPages - 1), offset.coerceAtLeast(0))
            }
        }
    }
    
    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, totalPages, loading) {
        if (!loading && totalPages > 0) {
            val page = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            viewModel.readerPositions[initialUri.toString()] = MainViewModel.ReaderPosition(page, offset)
            if (isRememberPositionEnabled && documentId.isNotEmpty()) {
                context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit()
                    .putInt("page_$documentId", page).putInt("offset_$documentId", offset).apply()
            }
        }
    }

    if (showNavigation) {
        AlertDialog(
            onDismissRequest = { showNavigation = false },
            title = { Text("Navigate document") },
            text = {
                Column {
                    OutlinedTextField(jumpInput, { jumpInput = it }, label = { Text("Page (1–$totalPages)") },
                        singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    TextButton(enabled = jumpInput.toIntOrNull()?.let { it in 1..totalPages } == true, onClick = {
                        coroutineScope.launch { listState.scrollToItem(jumpInput.toInt() - 1) }; showNavigation = false
                    }) { Text("Go to page") }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        item { Text("Table of contents", style = MaterialTheme.typography.titleSmall) }
                        val rows = com.pdfchemy.app.logic.ReaderNavigation.flattenOutline(bookmarks)
                        items(rows.size) { i ->
                            val row = rows[i]
                            TextButton(modifier = Modifier.padding(start = (row.depth * 12).dp), onClick = {
                                coroutineScope.launch { listState.scrollToItem((row.bookmark.pageNumber - 1).coerceIn(0, totalPages - 1)) }; showNavigation = false
                            }) { Text("${row.bookmark.title} · ${row.bookmark.pageNumber}") }
                        }
                        item { Text("Your bookmarks", style = MaterialTheme.typography.titleSmall) }
                        items(personalBookmarks.size) { i ->
                            val page = personalBookmarks.sorted()[i]
                            TextButton(onClick = { coroutineScope.launch { listState.scrollToItem(page.coerceIn(0, totalPages - 1)) }; showNavigation = false }) { Text("Page ${page + 1}") }
                        }
                        item { Text("Pages", style = MaterialTheme.typography.titleSmall) }
                        items(totalPages) { page ->
                            Column(Modifier.clickable { coroutineScope.launch { listState.scrollToItem(page) }; showNavigation = false }.padding(8.dp)) {
                                Text("Page ${page + 1}")
                                PdfReaderPageItem(context, selectedPdfUri!!, page, 240)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNavigation = false }) { Text("Close") } }
        )
    }

    if (showVanguardBlockedDialog || showVanguardDamagedDialog || showVanguardEncryptedDialog) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(if (showVanguardBlockedDialog) stringResource(R.string.vanguard_blocked_title) else "Document unavailable") },
            text = { Text(when {
                showVanguardEncryptedDialog -> "This PDF is password protected. Unlock a copy before reading."
                showVanguardDamagedDialog -> "This PDF could not be parsed. Try Repair PDF or another copy."
                else -> "Active executable content was detected. Select another document."
            }) },
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
        if (loading || loadFailure != null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) CircularProgressIndicator() else Text(loadFailure!!)
                if (!loading) TextButton(onClick = { retry++ }) { Text("Retry") }
            }
        }
        if (!loading && totalPages > 0 && selectedPdfUri != null) {
            // Interactive Reader Surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            if (newScale == 1f && scale == 1f) {
                                // Delegate pan to LazyColumn scrolling when not zoomed
                                coroutineScope.launch {
                                    listState.scrollBy(-pan.y)
                                }
                            } else {
                                // Calculate bounding offsets to keep document in view
                                val maxOffsetX = (size.width * newScale - size.width) / 2
                                val maxOffsetY = (size.height * newScale - size.height) / 2
                                offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                            }
                            scale = newScale
                        }
                    }
            ) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false, // We control scroll via transform gestures for perfect sync
                    contentPadding = PaddingValues(top = if (showControls) 80.dp else 0.dp, bottom = if (showControls) 120.dp else 0.dp, start = 16.dp, end = 16.dp),
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
                        PdfReaderPageItem(context, selectedPdfUri!!, index, fitPage = fitPage)
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
                    IconButton(onClick = { isSearchMode = !isSearchMode }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search")
                    }
                    IconButton(enabled = totalPages > 0, onClick = { jumpInput = "${listState.firstVisibleItemIndex + 1}"; showNavigation = true }) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Pages, table of contents and bookmarks")
                    }
                    var showMore by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMore = true }) { Icon(Icons.Rounded.MoreVert, "Reading actions") }
                        DropdownMenu(showMore, { showMore = false }) {
                            DropdownMenuItem(text = { Text(if (fitPage) "Fit width" else "Fit page") }, onClick = { fitPage = !fitPage; showMore = false })
                            DropdownMenuItem(text = { Text(if (listState.firstVisibleItemIndex in personalBookmarks) "Remove bookmark" else "Bookmark page") }, onClick = {
                                val page = listState.firstVisibleItemIndex
                                personalBookmarks = if (page in personalBookmarks) personalBookmarks - page else personalBookmarks + page
                                context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE).edit().putStringSet("bookmarks_$documentId", personalBookmarks.map { it.toString() }.toSet()).apply()
                                showMore = false
                            }, enabled = totalPages > 0)
                            DropdownMenuItem(text = { Text("Share") }, onClick = {
                                runCatching { com.pdfchemy.app.logic.DocumentActions.share(context, selectedPdfUri!!) }.onFailure { viewModel.notifyError("No sharing app is available. Save a copy and try again.") }; showMore = false
                            }, enabled = totalPages > 0)
                            DropdownMenuItem(text = { Text("Print") }, onClick = {
                                runCatching { com.pdfchemy.app.logic.DocumentActions.print(context, selectedPdfUri!!, fileName) }.onFailure { viewModel.notifyError("Printing is unavailable. Check your print service and try again.") }; showMore = false
                            }, enabled = totalPages > 0)
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
                            onValueChange = { searchQuery = it; searchGeneration.invalidate(); searchMatches = emptyList(); isSearching = false },
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
                                    val query = searchQuery
                                    val ticket = searchGeneration.begin()
                                    isSearching = true
                                    coroutineScope.launch {
                                        try {
                                            val summary = com.pdfchemy.app.logic.PdfFindAndReplaceEngine.findOccurrences(context, initialUri, query)
                                            if (!searchGeneration.isCurrent(ticket)) return@launch
                                            searchMatches = summary.occurrences
                                            currentMatchIndex = 0
                                            if (searchMatches.isNotEmpty()) {
                                                listState.animateScrollToItem(searchMatches[0].pageIndex)
                                            }
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (_: Exception) { if (searchGeneration.isCurrent(ticket)) viewModel.notifyError("Search failed. Check document access and retry.") }
                                        finally { if (searchGeneration.isCurrent(ticket)) isSearching = false }
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
                                }) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous match") }
                                IconButton(onClick = {
                                    val newIdx = if (currentMatchIndex < searchMatches.size - 1) currentMatchIndex + 1 else 0
                                    currentMatchIndex = newIdx
                                    coroutineScope.launch { listState.animateScrollToItem(searchMatches[newIdx].pageIndex) }
                                }) { Icon(Icons.Rounded.KeyboardArrowDown, "Next match") }
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
fun PdfReaderPageItem(context: Context, uri: Uri, pageIndex: Int, requestedWidth: Int? = null, fitPage: Boolean = false) {
    var bitmap by remember(uri, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri, pageIndex) { mutableStateOf(false) }
    var retryPage by remember(uri, pageIndex) { mutableIntStateOf(0) }
    
    LaunchedEffect(uri, pageIndex, retryPage) {
        val targetWidth = requestedWidth ?: (context.resources.displayMetrics.widthPixels * 1.5).toInt()
        failed = false
        try {
            bitmap = NativeRendererCoordinator.renderUriToBitmap(context, uri, pageIndex, targetWidth)
            failed = bitmap == null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    
    DisposableEffect(bitmap) {
        onDispose {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .shadow(4.dp, RoundedCornerShape(4.dp))
            .background(Color.White)
            .clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = if (fitPage) ContentScale.Fit else ContentScale.FillWidth,
                modifier = if (fitPage) Modifier.fillMaxWidth().height((androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 140).coerceAtLeast(200).dp) else Modifier.fillMaxWidth()
            )
        } else {
            // Placeholder while loading
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f) // approximate portrait ratio
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (failed) TextButton(onClick = { retryPage++ }) { Text("Retry page ${pageIndex + 1}") }
                else CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        }
    }
}
