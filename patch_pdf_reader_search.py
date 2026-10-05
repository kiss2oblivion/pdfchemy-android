import sys
with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Add Search state variables
target_vars = """    val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    var bookmarks by remember { mutableStateOf<List<com.pdfchemy.app.logic.OutlineBookmark>>(emptyList()) }"""
replacement_vars = target_vars + """
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<com.pdfchemy.app.logic.TextMatchOccurrence>>(emptyList()) }
    var currentMatchIndex by remember { mutableStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }"""
if target_vars in text:
    text = text.replace(target_vars, replacement_vars)

# 2. Add Search icon to TopAppBar
target_appbar = """                actions = {
                    if (bookmarks.isNotEmpty()) {"""
replacement_appbar = """                actions = {
                    IconButton(onClick = { isSearchMode = !isSearchMode }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search")
                    }
                    if (bookmarks.isNotEmpty()) {"""
if target_appbar in text:
    text = text.replace(target_appbar, replacement_appbar)

# 3. Add Search Bar below TopAppBar
target_search_bar = """        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeOut(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {"""
replacement_search_bar = """        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeOut(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {"""
if target_search_bar in text:
    text = text.replace(target_search_bar, replacement_search_bar)
else:
    target_search_bar = """        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {"""
    replacement_search_bar = """        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {"""
    if target_search_bar in text:
        text = text.replace(target_search_bar, replacement_search_bar)

# We must close the Column and add the search bar UI inside it.
target_topbar_end = """                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            )"""
replacement_topbar_end = """                colors = TopAppBarDefaults.topAppBarColors(
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
                                    scope.launch {
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
                                    scope.launch { listState.animateScrollToItem(searchMatches[newIdx].pageIndex) }
                                }) { Icon(androidx.compose.material.icons.filled.KeyboardArrowUp, null) }
                                IconButton(onClick = {
                                    val newIdx = if (currentMatchIndex < searchMatches.size - 1) currentMatchIndex + 1 else 0
                                    currentMatchIndex = newIdx
                                    scope.launch { listState.animateScrollToItem(searchMatches[newIdx].pageIndex) }
                                }) { Icon(androidx.compose.material.icons.filled.KeyboardArrowDown, null) }
                            }
                        }
                    }
                }
            }
            }"""
if target_topbar_end in text:
    text = text.replace(target_topbar_end, replacement_topbar_end)

with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
