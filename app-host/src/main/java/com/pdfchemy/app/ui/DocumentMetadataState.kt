package com.pdfchemy.app.ui

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.pdfchemy.app.utils.FileUtils
import com.pdfchemy.app.utils.ProviderMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DocumentMetadata(val name: String?, val mime: String)

/** Keying the state itself prevents even one frame of A's label under a newly selected B. */
@Composable
internal fun rememberDocumentMetadata(uri: Uri): DocumentMetadata {
    val context = LocalContext.current
    var value by remember(uri) { mutableStateOf(DocumentMetadata(
        FileUtils.getFileName(context, uri), FileUtils.getMimeType(context, uri))) }
    LaunchedEffect(uri) {
        ProviderMetadata.load(context, uri)
        withContext(Dispatchers.Main.immediate) {
            value = DocumentMetadata(FileUtils.getFileName(context, uri), FileUtils.getMimeType(context, uri))
        }
    }
    return value
}
