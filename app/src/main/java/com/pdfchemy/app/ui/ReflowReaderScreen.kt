package com.pdfchemy.app.ui

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.OutlineBookmark
import com.pdfchemy.app.logic.PdfOutlineReader
import com.pdfchemy.app.logic.ReflowSection
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

enum class ReaderTheme(val bg: Color, val text: Color, val label: String) {
    LIGHT(Color(0xFFFFFFFF), Color(0xFF1E293B), "Light"),
    SEPIA(Color(0xFFFBF0D9), Color(0xFF5F4B32), "Sepia"),
    DARK(Color(0xFF1E293B), Color(0xFFF1F5F9), "Dark"),
    OLED(Color(0xFF000000), Color(0xFFE2E8F0), "OLED")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReflowReaderScreen(
    initialUri: Uri? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialUri) }
    var uriHash by remember { mutableStateOf("default") }
    var reflowSections by remember { mutableStateOf<List<ReflowSection>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<OutlineBookmark>>(emptyList()) }
    var isScannedOnly by remember { mutableStateOf(false) }
    var isOcrExtracting by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.screenWidthDp >= 600

    val postureInfo = com.pdfchemy.app.logic.rememberDevicePosture()
    var forceTabletopMode by remember { mutableStateOf(false) }
    val isTabletopMode = postureInfo.isTabletop || forceTabletopMode

    val prefs = remember(context) { context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE) }
    var isVanguardEnabled by remember {
        mutableStateOf(prefs.getBoolean("vanguard_enabled", true))
    }
    androidx.compose.runtime.DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "vanguard_enabled") {
                isVanguardEnabled = prefs.getBoolean("vanguard_enabled", true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    var showVanguardBlockedDialog by remember { mutableStateOf(false) }
    var showVanguardEncryptedDialog by remember { mutableStateOf(false) }
    var isVanguardScanning by remember { mutableStateOf(false) }
    var vanguardScanningFileName by remember { mutableStateOf<String?>(null) }

    if (showVanguardBlockedDialog) {
        AlertDialog(
            onDismissRequest = {
                showVanguardBlockedDialog = false
                if (initialUri != null) onBack()
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_blocked_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_blocked_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVanguardBlockedDialog = false
                        if (initialUri != null) onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    if (showVanguardEncryptedDialog) {
        AlertDialog(
            onDismissRequest = {
                showVanguardEncryptedDialog = false
                if (initialUri != null) onBack()
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_title),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.vanguard_encrypted_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVanguardEncryptedDialog = false
                        if (initialUri != null) onBack()
                    }
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    VanguardScanningOverlay(
        visible = isVanguardScanning,
        fileName = vanguardScanningFileName
    )

    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            val isPdf = initialUri.toString().lowercase().endsWith(".pdf") || 
                (com.pdfchemy.app.utils.FileUtils.getFileName(context, initialUri)?.lowercase()?.endsWith(".pdf") == true)
            if (isPdf && isVanguardEnabled) {
                isVanguardScanning = true
                vanguardScanningFileName = com.pdfchemy.app.utils.FileUtils.getFileName(context, initialUri)
                try {
                    val threat = com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, initialUri)
                    when (threat) {
                        is com.pdfchemy.app.logic.VanguardThreatResult.Clean -> {
                            isLoading = true
                            val docData = PdfOutlineReader.loadReflowDocument(context, initialUri)
                            reflowSections = docData.sections
                            bookmarks = docData.bookmarks
                            isScannedOnly = docData.isScannedOnly
                            uriHash = initialUri.toString().hashCode().toString()
                            isLoading = false
                        }
                        is com.pdfchemy.app.logic.VanguardThreatResult.EncryptedCannotVerify -> {
                            showVanguardEncryptedDialog = true
                        }
                        is com.pdfchemy.app.logic.VanguardThreatResult.ExecutableThreat,
                        is com.pdfchemy.app.logic.VanguardThreatResult.ParseFailed -> {
                            showVanguardBlockedDialog = true
                        }
                    }
                } finally {
                    isVanguardScanning = false
                    vanguardScanningFileName = null
                }
            } else {
                isLoading = true
                val docData = PdfOutlineReader.loadReflowDocument(context, initialUri)
                reflowSections = docData.sections
                bookmarks = docData.bookmarks
                isScannedOnly = docData.isScannedOnly
                uriHash = initialUri.toString().hashCode().toString()
                isLoading = false
            }
        }
    }

    var selectedTheme by remember { mutableStateOf(ReaderTheme.LIGHT) }
    var fontSizeSp by remember { mutableStateOf(16f) }
    var useSerifFont by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()

    LaunchedEffect(reflowSections, uriHash) {
        if (reflowSections.isNotEmpty() && !isScannedOnly) {
            val savedIndex = prefs.getInt("reader_scroll_index_$uriHash", 0)
            val savedOffset = prefs.getInt("reader_scroll_offset_$uriHash", 0)
            if (savedIndex < reflowSections.size) {
                listState.scrollToItem(savedIndex, savedOffset)
            }
        }
    }

    LaunchedEffect(listState, uriHash, reflowSections) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (reflowSections.isNotEmpty() && !isScannedOnly) {
                    prefs.edit()
                        .putInt("reader_scroll_index_$uriHash", index)
                        .putInt("reader_scroll_offset_$uriHash", offset)
                        .apply()
                }
            }
    }

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    var isTtsBarVisible by remember { mutableStateOf(false) }
    var isTtsPlaying by remember { mutableStateOf(false) }
    var ttsRate by remember { mutableFloatStateOf(1.0f) }
    var currentSpeakingIndex by remember { mutableIntStateOf(0) }
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }

    val allParagraphs = remember(reflowSections) {
        reflowSections.flatMap { it.paragraphs }
    }
    
    val paragraphToSection = remember(reflowSections) {
        val map = mutableMapOf<Int, Int>()
        var pIdx = 0
        for ((sIdx, section) in reflowSections.withIndex()) {
            for (p in section.paragraphs) {
                map[pIdx++] = sIdx
            }
        }
        map
    }

    fun speakParagraph(index: Int) {
        if (index in allParagraphs.indices && ttsEngine != null) {
            currentSpeakingIndex = index
            val text = allParagraphs[index]
            val params = android.os.Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "para_$index")
            ttsEngine?.setSpeechRate(ttsRate)
            ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "para_$index")
            isTtsPlaying = true
        } else {
            isTtsPlaying = false
        }
    }

    // Initialize TTS
    DisposableEffect(context) {
        val tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine?.language = Locale.getDefault()
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) { 
                    isTtsPlaying = true 
                    val sIdx = paragraphToSection[currentSpeakingIndex]
                    if (sIdx != null && !listState.isScrollInProgress) {
                        listState.animateScrollToItem(sIdx)
                    }
                }
            }
            override fun onDone(utteranceId: String?) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (currentSpeakingIndex + 1 < allParagraphs.size) {
                        currentSpeakingIndex++
                        val nextText = allParagraphs[currentSpeakingIndex]
                        tts.speak(nextText, TextToSpeech.QUEUE_FLUSH, null, "P_$currentSpeakingIndex")
                    } else {
                        isTtsPlaying = false
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) { isTtsPlaying = false }
            }
        })
        ttsEngine = tts

        onDispose {
            tts.stop()
            tts.shutdown()
            ttsEngine = null
        }
    }

    val searchMatches = remember(searchQuery, reflowSections) {
        val q = searchQuery.trim()
        if (q.length < 2) emptyList()
        else {
            val list = mutableListOf<Int>()
            reflowSections.forEachIndexed { sIdx, section ->
                val hasMatch = section.paragraphs.any { p -> p.contains(q, ignoreCase = true) }
                if (hasMatch) list.add(sIdx)
            }
            list
        }
    }

    val onPreviousMatch = {
        if (searchMatches.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val newIdx = if (currentMatchIndex > 0) currentMatchIndex - 1 else searchMatches.size - 1
            currentMatchIndex = newIdx
            scope.launch {
                listState.animateScrollToItem(searchMatches[newIdx])
            }
        }
    }

    val onNextMatch = {
        if (searchMatches.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val newIdx = if (currentMatchIndex < searchMatches.size - 1) currentMatchIndex + 1 else 0
            currentMatchIndex = newIdx
            scope.launch {
                listState.animateScrollToItem(searchMatches[newIdx])
            }
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val isPdf = uri.toString().lowercase().endsWith(".pdf") || 
                (com.pdfchemy.app.utils.FileUtils.getFileName(context, uri)?.lowercase()?.endsWith(".pdf") == true)
            scope.launch {
                if (isPdf && isVanguardEnabled) {
                    isVanguardScanning = true
                    vanguardScanningFileName = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri)
                    try {
                        val threat = com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, uri)
                        when (threat) {
                            is com.pdfchemy.app.logic.VanguardThreatResult.Clean -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedPdfUri = uri
                                isLoading = true
                                val docData = PdfOutlineReader.loadReflowDocument(context, uri)
                                reflowSections = docData.sections
                                bookmarks = docData.bookmarks
                                isScannedOnly = docData.isScannedOnly
                                uriHash = uri.toString().hashCode().toString()
                                isLoading = false
                            }
                            is com.pdfchemy.app.logic.VanguardThreatResult.EncryptedCannotVerify -> {
                                showVanguardEncryptedDialog = true
                            }
                            is com.pdfchemy.app.logic.VanguardThreatResult.ExecutableThreat,
                            is com.pdfchemy.app.logic.VanguardThreatResult.ParseFailed -> {
                                showVanguardBlockedDialog = true
                            }
                        }
                    } finally {
                        isVanguardScanning = false
                        vanguardScanningFileName = null
                    }
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    selectedPdfUri = uri
                    isLoading = true
                    val docData = PdfOutlineReader.loadReflowDocument(context, uri)
                    reflowSections = docData.sections
                    bookmarks = docData.bookmarks
                    isScannedOnly = docData.isScannedOnly
                    uriHash = uri.toString().hashCode().toString()
                    isLoading = false
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = bookmarks.isNotEmpty(),
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Text(
                    text = "Table of Contents",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
                if (bookmarks.isEmpty()) {
                    Text(
                        text = "No document outlines found in this PDF.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(bookmarks, key = { "${it.title}_${it.pageNumber}" }) { bookmark ->
                            NavigationDrawerItem(
                                label = { Text(bookmark.title, maxLines = 1) },
                                badge = { Text("P.${bookmark.pageNumber}") },
                                selected = false,
                                onClick = {
                                    scope.launch {
                                        drawerState.close()
                                        val targetIdx = (bookmark.pageNumber - 1).coerceIn(0, (reflowSections.size - 1).coerceAtLeast(0))
                                        listState.animateScrollToItem(targetIdx)
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = selectedTheme.bg,
            topBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TopAppBar(
                        title = {
                            Text(
                                text = if (selectedPdfUri != null) FileUtils.getFileName(context, selectedPdfUri!!) ?: "Reader" else stringResource(R.string.menu_reflow_reader),
                                color = selectedTheme.text,
                                maxLines = 1
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = selectedTheme.bg,
                            scrolledContainerColor = selectedTheme.bg
                        ),
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back), tint = selectedTheme.text)
                            }
                        },
                        actions = {
                            if (bookmarks.isNotEmpty()) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Rounded.MenuBook, contentDescription = "Table of Contents", tint = selectedTheme.text)
                                }
                            }
                            if (selectedPdfUri != null && reflowSections.isNotEmpty()) {
                                IconButton(onClick = {
                                    isTtsBarVisible = !isTtsBarVisible
                                    if (isTtsBarVisible && !isTtsPlaying) {
                                        speakParagraph(currentSpeakingIndex)
                                    } else if (!isTtsBarVisible) {
                                        ttsEngine?.stop()
                                        isTtsPlaying = false
                                    }
                                }) {
                                    Icon(
                                        if (isTtsPlaying) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeMute,
                                        contentDescription = "Read Aloud",
                                        tint = if (isTtsBarVisible) MaterialTheme.colorScheme.primary else selectedTheme.text
                                    )
                                }
                                IconButton(onClick = {
                                    isSearchActive = !isSearchActive
                                    if (!isSearchActive) searchQuery = ""
                                }) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = "Search Text",
                                        tint = if (isSearchActive) MaterialTheme.colorScheme.primary else selectedTheme.text
                                    )
                                }
                            }
                            IconButton(onClick = { forceTabletopMode = !forceTabletopMode }) {
                                Icon(
                                    imageVector = if (isTabletopMode) Icons.Rounded.LaptopMac else Icons.Rounded.PhoneAndroid,
                                    contentDescription = stringResource(if (isTabletopMode) R.string.flip_fullscreen_mode else R.string.flip_tabletop_mode),
                                    tint = if (isTabletopMode) MaterialTheme.colorScheme.primary else selectedTheme.text
                                )
                            }
                            IconButton(onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf", "application/epub+zip", "application/zip", "application/octet-stream")) }) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = "Open Document", tint = selectedTheme.text)
                            }
                        }
                    )

                    AnimatedVisibility(
                        visible = isSearchActive,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            color = selectedTheme.bg,
                            tonalElevation = 4.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = {
                                        searchQuery = it
                                        currentMatchIndex = 0
                                        if (it.trim().length >= 2) {
                                            val firstMatch = reflowSections.indexOfFirst { s ->
                                                s.paragraphs.any { p -> p.contains(it.trim(), ignoreCase = true) }
                                            }
                                            if (firstMatch >= 0) {
                                                scope.launch { listState.animateScrollToItem(firstMatch) }
                                            }
                                        }
                                    },
                                    placeholder = { Text("Find text in document...", fontSize = 13.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(Icons.Rounded.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = selectedTheme.text,
                                        unfocusedTextColor = selectedTheme.text
                                    )
                                )

                                if (searchQuery.trim().length >= 2) {
                                    Text(
                                        text = if (searchMatches.isEmpty()) "0" else "${currentMatchIndex + 1}/${searchMatches.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (searchMatches.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )

                                    IconButton(
                                        onClick = onPreviousMatch,
                                        enabled = searchMatches.isNotEmpty(),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Rounded.ExpandLess, contentDescription = "Previous Match", tint = selectedTheme.text)
                                    }

                                    IconButton(
                                        onClick = onNextMatch,
                                        enabled = searchMatches.isNotEmpty(),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Rounded.ExpandMore, contentDescription = "Next Match", tint = selectedTheme.text)
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = isTtsBarVisible,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Surface(
                            color = selectedTheme.bg,
                            tonalElevation = 4.dp,
                            shadowElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (currentSpeakingIndex > 0) {
                                                speakParagraph(currentSpeakingIndex - 1)
                                            }
                                        },
                                        enabled = currentSpeakingIndex > 0,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous Paragraph", tint = selectedTheme.text)
                                    }

                                    IconButton(
                                        onClick = {
                                            if (isTtsPlaying) {
                                                ttsEngine?.stop()
                                                isTtsPlaying = false
                                            } else {
                                                speakParagraph(currentSpeakingIndex)
                                            }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            if (isTtsPlaying) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle,
                                            contentDescription = "Play/Pause",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            if (currentSpeakingIndex + 1 < allParagraphs.size) {
                                                speakParagraph(currentSpeakingIndex + 1)
                                            }
                                        },
                                        enabled = currentSpeakingIndex + 1 < allParagraphs.size,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next Paragraph", tint = selectedTheme.text)
                                    }
                                }

                                Text(
                                    text = "${currentSpeakingIndex + 1}/${allParagraphs.size.coerceAtLeast(1)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = selectedTheme.text.copy(alpha = 0.7f)
                                )

                                TextButton(onClick = {
                                    ttsRate = when (ttsRate) {
                                        0.75f -> 1.0f
                                        1.0f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2.0f
                                        else -> 0.75f
                                    }
                                    ttsEngine?.setSpeechRate(ttsRate)
                                }) {
                                    Text("${ttsRate}x", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (selectedPdfUri != null && reflowSections.isNotEmpty() && !isTabletopMode) {
                    Surface(
                        color = selectedTheme.bg,
                        tonalElevation = 6.dp,
                        shadowElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Theme Selector
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ReaderTheme.values().forEach { theme ->
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(theme.bg, CircleShape)
                                                .clickable {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    selectedTheme = theme
                                                }
                                                .padding(2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (selectedTheme == theme) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = theme.text
                                                )
                                            }
                                        }
                                    }
                                }

                                // Font Sizing Controls
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = { fontSizeSp = (fontSizeSp - 2).coerceAtLeast(12f) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("A-", color = selectedTheme.text, fontWeight = FontWeight.Bold)
                                    }
                                    Text("${fontSizeSp.toInt()}sp", color = selectedTheme.text, style = MaterialTheme.typography.bodySmall)
                                    IconButton(
                                        onClick = { fontSizeSp = (fontSizeSp + 2).coerceAtMost(32f) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text("A+", color = selectedTheme.text, fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(
                                        onClick = { useSerifFont = !useSerifFont },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text(if (useSerifFont) "Serif" else "Sans", color = selectedTheme.text, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (selectedPdfUri != null && isScannedOnly && reflowSections.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 500.dp)
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.DocumentScanner,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        Text(
                            text = stringResource(R.string.scanned_pdf_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = selectedTheme.text
                        )

                        Text(
                            text = stringResource(R.string.scanned_pdf_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = selectedTheme.text.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )

                        if (isOcrExtracting) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.running_ocr), color = selectedTheme.text)
                        } else {
                            Button(
                                onClick = {
                                    isOcrExtracting = true
                                    scope.launch {
                                        val extractedText = com.pdfchemy.app.logic.PdfTextExtractor.extractUsingOcr(context, selectedPdfUri!!)
                                        if (extractedText.isNotBlank()) {
                                            val paragraphs = extractedText.split(Regex("\n\n+"))
                                                .map { it.replace(Regex("\n+"), " ").trim() }
                                                .filter { it.isNotBlank() }
                                            
                                            reflowSections = listOf(ReflowSection(pageNumber = 1, title = "OCR Extracted", paragraphs = paragraphs))
                                            isScannedOnly = false
                                        }
                                        isOcrExtracting = false
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 50.dp)
                            ) {
                                Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.run_on_device_ocr), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (selectedPdfUri == null) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 500.dp)
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = stringResource(R.string.reflow_reader_headline),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = stringResource(R.string.reflow_reader_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Button(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf", "application/epub+zip", "application/zip", "application/octet-stream")) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.select_pdf_for_reading),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else if (isTabletopMode) {
                    // Tabletop Mode for Flip phones: Reading on top, Desk Controls on bottom
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Reading Viewport (above hinge)
                        Box(
                            modifier = Modifier
                                .weight(1.15f)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .widthIn(max = 680.dp)
                                    .fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(reflowSections, key = { it.pageNumber }, contentType = { "section" }) { section ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            color = selectedTheme.text.copy(alpha = 0.08f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "PAGE ${section.pageNumber}",
                                                color = selectedTheme.text.copy(alpha = 0.6f),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }

                                        section.paragraphs.forEach { p ->
                                            val isSpoken = isTtsPlaying && p == allParagraphs.getOrNull(currentSpeakingIndex)
                                            val bgColor = if (isSpoken) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
                                            
                                            Box(modifier = Modifier.background(bgColor, RoundedCornerShape(4.dp)).padding(2.dp)) {
                                                HighlightedParagraph(
                                                    text = p,
                                                    searchQuery = searchQuery,
                                                    textColor = selectedTheme.text,
                                                    fontSizeSp = fontSizeSp,
                                                    useSerifFont = useSerifFont
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Horizontal Hinge Divider
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(selectedTheme.text.copy(alpha = 0.08f))
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                Text(
                                    text = stringResource(R.string.flip_tabletop_active_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "${fontSizeSp.toInt()}sp • ${selectedTheme.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = selectedTheme.text.copy(alpha = 0.7f)
                            )
                        }

                        // Bottom Desk Controller
                        Surface(
                            modifier = Modifier
                                .weight(0.85f)
                                .fillMaxWidth(),
                            color = selectedTheme.bg
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.SpaceEvenly,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Theme Selector
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        ReaderTheme.values().forEach { theme ->
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(theme.bg, CircleShape)
                                                    .clickable {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        selectedTheme = theme
                                                    }
                                                    .border(
                                                        width = if (selectedTheme == theme) 2.dp else 1.dp,
                                                        color = if (selectedTheme == theme) MaterialTheme.colorScheme.primary else selectedTheme.text.copy(alpha = 0.2f),
                                                        shape = CircleShape
                                                    )
                                                    .padding(2.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (selectedTheme == theme) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp),
                                                        tint = theme.text
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Font Sizing Controls
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalButton(
                                        onClick = { fontSizeSp = (fontSizeSp - 2).coerceAtLeast(12f) },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text("A-", fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text("${fontSizeSp.toInt()} sp", color = selectedTheme.text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    FilledTonalButton(
                                        onClick = { fontSizeSp = (fontSizeSp + 2).coerceAtMost(32f) },
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text("A+", fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    OutlinedButton(
                                        onClick = { useSerifFont = !useSerifFont },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text(if (useSerifFont) "Serif" else "Sans", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                } else if (isWideScreen) {
                    // Two-Page Physical Book Spread on Fold & Tablet
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .widthIn(max = 1100.dp)
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(reflowSections, key = { it.pageNumber }, contentType = { "section" }) { section ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(selectedTheme.text.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    color = selectedTheme.text.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "PAGE ${section.pageNumber}",
                                        color = selectedTheme.text.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }

                                section.paragraphs.forEach { p ->
                                    val isSpoken = isTtsPlaying && p == allParagraphs.getOrNull(currentSpeakingIndex)
                                    val bgColor = if (isSpoken) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
                                    
                                    Box(modifier = Modifier.background(bgColor, RoundedCornerShape(4.dp)).padding(2.dp)) {
                                        HighlightedParagraph(
                                            text = p,
                                            searchQuery = searchQuery,
                                            textColor = selectedTheme.text,
                                            fontSizeSp = fontSizeSp,
                                            useSerifFont = useSerifFont
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(reflowSections, key = { it.pageNumber }, contentType = { "section" }) { section ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    color = selectedTheme.text.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "PAGE ${section.pageNumber}",
                                        color = selectedTheme.text.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }

                                section.paragraphs.forEach { p ->
                                    val isSpoken = isTtsPlaying && p == allParagraphs.getOrNull(currentSpeakingIndex)
                                    val bgColor = if (isSpoken) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent
                                    
                                    Box(modifier = Modifier.background(bgColor, RoundedCornerShape(4.dp)).padding(2.dp)) {
                                        HighlightedParagraph(
                                            text = p,
                                            searchQuery = searchQuery,
                                            textColor = selectedTheme.text,
                                            fontSizeSp = fontSizeSp,
                                            useSerifFont = useSerifFont
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = selectedTheme.text.copy(alpha = 0.1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightedParagraph(
    text: String,
    searchQuery: String,
    textColor: Color,
    fontSizeSp: Float,
    useSerifFont: Boolean
) {
    val trimmed = searchQuery.trim()
    if (trimmed.length < 2 || !text.contains(trimmed, ignoreCase = true)) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.55f).sp,
            fontFamily = if (useSerifFont) FontFamily.Serif else FontFamily.SansSerif,
            textAlign = TextAlign.Start
        )
    } else {
        val annotated = remember(text, trimmed) {
            buildAnnotatedString {
                var startIndex = 0
                val lowerText = text.lowercase()
                val lowerQuery = trimmed.lowercase()
                while (startIndex < text.length) {
                    val matchIdx = lowerText.indexOf(lowerQuery, startIndex)
                    if (matchIdx == -1) {
                        append(text.substring(startIndex))
                        break
                    }
                    if (matchIdx > startIndex) {
                        append(text.substring(startIndex, matchIdx))
                    }
                    val matchEnd = matchIdx + trimmed.length
                    pushStyle(
                        SpanStyle(
                            background = Color(0xFFFFD54F),
                            color = Color(0xFF1E293B),
                            fontWeight = FontWeight.Bold
                        )
                    )
                    append(text.substring(matchIdx, matchEnd))
                    pop()
                    startIndex = matchEnd
                }
            }
        }
        Text(
            text = annotated,
            color = textColor,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.55f).sp,
            fontFamily = if (useSerifFont) FontFamily.Serif else FontFamily.SansSerif,
            textAlign = TextAlign.Start
        )
    }
}
