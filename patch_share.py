import sys
with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'r', encoding='utf-8') as f:
    text = f.read()

target = """                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors("""
                
replacement = """                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (initialUri != null) {
                        IconButton(onClick = { com.pdfchemy.app.utils.FileUtils.sharePdf(context, initialUri) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors("""

if target in text:
    text = text.replace(target, replacement)
else:
    # try without auto mirrored
    target = """                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors("""
    replacement = """                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (initialUri != null) {
                        IconButton(onClick = { com.pdfchemy.app.utils.FileUtils.sharePdf(context, initialUri) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors("""
    if target in text:
        text = text.replace(target, replacement)
    else:
        # maybe it is Icons.Rounded.Edit?
        target = """                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors("""
        replacement = """                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (initialUri != null) {
                        IconButton(onClick = { com.pdfchemy.app.utils.FileUtils.sharePdf(context, initialUri) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors("""
        if target in text:
            text = text.replace(target, replacement)

with open('app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt', 'w', encoding='utf-8', newline='\n') as f:
    f.write(text)
