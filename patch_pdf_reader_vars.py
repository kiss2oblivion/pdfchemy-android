import sys
with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

target_vars = """    var showControls by remember { mutableStateOf(true) }"""
replacement_vars = target_vars + """
    
    // Search and TOC state
    val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    var bookmarks by remember { mutableStateOf<List<com.pdfchemy.app.logic.OutlineBookmark>>(emptyList()) }
    var isSearchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchMatches by remember { mutableStateOf<List<com.pdfchemy.app.logic.TextMatchOccurrence>>(emptyList()) }
    var currentMatchIndex by remember { mutableStateOf(0) }
    var isSearching by remember { mutableStateOf(false) }"""
if target_vars in text:
    text = text.replace(target_vars, replacement_vars)

# Replace 'scope.' with 'coroutineScope.' for search matches and drawer
text = text.replace('scope.launch { drawerState.open() }', 'coroutineScope.launch { drawerState.open() }')
text = text.replace('scope.launch { drawerState.close()', 'coroutineScope.launch { drawerState.close()')
text = text.replace('scope.launch {\n                                        try {\n                                            val summary', 'coroutineScope.launch {\n                                        try {\n                                            val summary')
text = text.replace('scope.launch { listState.animateScrollToItem(searchMatches[newIdx].pageIndex) }', 'coroutineScope.launch { listState.animateScrollToItem(searchMatches[newIdx].pageIndex) }')
text = text.replace('scope.launch {\n                try {\n                    bookmarks', 'coroutineScope.launch {\n                try {\n                    bookmarks')

with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
