import sys
with open('app-host/src/main/java/com/pdfchemy/app/ui/ReflowReaderScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

target1 = """fun ReflowReaderScreen(
    initialUri: Uri? = null,
    viewModel: com.pdfchemy.app.ui.MainViewModel,
    onBack: () -> Unit
) {
    SecureScreenContent()
    BackHandler { onBack() }
    val context = LocalContext.current"""
replacement1 = target1 + """
    val isRememberPositionEnabled by viewModel.isRememberPositionEnabled.collectAsState()"""
text = text.replace(target1, replacement1)

target2 = """    LaunchedEffect(reflowSections, uriHash) {
        if (reflowSections.isNotEmpty() && !isScannedOnly && hasReadableContent) {
            val savedIndex = prefs.getInt("reader_scroll_index_$uriHash", 0)
            val savedOffset = prefs.getInt("reader_scroll_offset_$uriHash", 0)
            if (savedIndex < reflowSections.size) {
                listState.scrollToItem(savedIndex, savedOffset)
            }
        }
    }"""
replacement2 = """    LaunchedEffect(reflowSections, uriHash) {
        if (isRememberPositionEnabled && reflowSections.isNotEmpty() && !isScannedOnly && hasReadableContent) {
            val savedIndex = prefs.getInt("reader_scroll_index_$uriHash", 0)
            val savedOffset = prefs.getInt("reader_scroll_offset_$uriHash", 0)
            if (savedIndex < reflowSections.size) {
                listState.scrollToItem(savedIndex, savedOffset)
            }
        }
    }"""
text = text.replace(target2, replacement2)

target3 = """    LaunchedEffect(listState, uriHash, reflowSections) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (reflowSections.isNotEmpty() && !isScannedOnly && hasReadableContent) {
                    prefs.edit()
                        .putInt("reader_scroll_index_$uriHash", index)
                        .putInt("reader_scroll_offset_$uriHash", offset)
                        .apply()
                }
            }
    }"""
replacement3 = """    LaunchedEffect(listState, uriHash, reflowSections, isRememberPositionEnabled) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (isRememberPositionEnabled && reflowSections.isNotEmpty() && !isScannedOnly && hasReadableContent) {
                    prefs.edit()
                        .putInt("reader_scroll_index_$uriHash", index)
                        .putInt("reader_scroll_offset_$uriHash", offset)
                        .apply()
                }
            }
    }"""
text = text.replace(target3, replacement3)

with open('app-host/src/main/java/com/pdfchemy/app/ui/ReflowReaderScreen.kt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
