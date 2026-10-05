import sys
with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Add drawerState, bookmarks, scope
target_vars = """    var showControls by remember { mutableStateOf(true) }
    var totalPages by remember { mutableStateOf(0) }"""
replacement_vars = target_vars + """
    val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    var bookmarks by remember { mutableStateOf<List<com.pdfchemy.app.logic.OutlineBookmark>>(emptyList()) }"""

if target_vars in text:
    text = text.replace(target_vars, replacement_vars)

# 2. Extract bookmarks asynchronously
target_launch = """            val docId = com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, initialUri)
            val stagedUri = withContext(Dispatchers.IO) { DocumentStager.stageDocumentCancellable(context, initialUri).uri }
            selectedPdfUri = stagedUri"""
replacement_launch = target_launch + """
            
            // Fetch bookmarks asynchronously
            scope.launch {
                try {
                    bookmarks = com.pdfchemy.app.logic.PdfOutlineReader.extractOutline(context, initialUri)
                } catch (e: Exception) {
                    // Ignore
                }
            }"""
if target_launch in text:
    text = text.replace(target_launch, replacement_launch)

# 3. Add Drawer icon in TopAppBar if bookmarks exist
target_appbar = """                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {"""
replacement_appbar = """                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (bookmarks.isNotEmpty()) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Rounded.Menu, contentDescription = "Table of Contents")
                        }
                    }"""
if target_appbar in text:
    text = text.replace(target_appbar, replacement_appbar)

# 4. Wrap Scaffold in ModalNavigationDrawer
target_scaffold = """    Scaffold(
        modifier = Modifier.fillMaxSize(),"""
replacement_scaffold = """    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = bookmarks.isNotEmpty(),
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Text(
                    text = "Table of Contents",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                androidx.compose.material3.HorizontalDivider()
                if (bookmarks.isEmpty()) {
                    Text(
                        text = "No document outlines found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(bookmarks.size) { index ->
                            val bookmark = bookmarks[index]
                            androidx.compose.material3.NavigationDrawerItem(
                                label = { Text(bookmark.title, maxLines = 1) },
                                badge = { Text("P.${bookmark.pageNumber}") },
                                selected = false,
                                onClick = {
                                    scope.launch {
                                        drawerState.close()
                                        val targetIdx = (bookmark.pageNumber - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
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
        modifier = Modifier.fillMaxSize(),"""
if target_scaffold in text:
    text = text.replace(target_scaffold, replacement_scaffold)
    
    # We must close the bracket of ModalNavigationDrawer after the Scaffold bracket.
    # The Scaffold bracket is closed at the very end of the composable.
    target_end = """    }
}"""
    # Replace the last occurrence
    idx = text.rfind(target_end)
    if idx != -1:
        text = text[:idx] + "    }\n    }\n}" + text[idx + len(target_end):]

with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
