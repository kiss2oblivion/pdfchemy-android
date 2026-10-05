import sys
with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

target1 = """    LaunchedEffect(initialUri) {
        guardDocumentLoad(onFailure = {
            showVanguardBlockedDialog = true
            isVanguardScanning = false
        }) {
            val stagedUri = withContext(Dispatchers.IO) { DocumentStager.stageDocumentCancellable(context, initialUri).uri }"""

replacement1 = """    val isRememberPositionEnabled by viewModel.isRememberPositionEnabled.collectAsState()
    
    LaunchedEffect(initialUri) {
        guardDocumentLoad(onFailure = {
            showVanguardBlockedDialog = true
            isVanguardScanning = false
        }) {
            val docId = com.pdfchemy.app.utils.DocumentIdentity.computeStableId(context, initialUri)
            val stagedUri = withContext(Dispatchers.IO) { DocumentStager.stageDocumentCancellable(context, initialUri).uri }"""

if target1 in text:
    text = text.replace(target1, replacement1)
else:
    print("target1 not found")

target2 = """            } else {
                totalPages = PdfEditor.getPageCount(context, stagedUri)
            }
        }
    }"""

replacement2 = """            } else {
                totalPages = PdfEditor.getPageCount(context, stagedUri)
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
    }"""

if target2 in text:
    text = text.replace(target2, replacement2)
else:
    print("target2 not found")

with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
