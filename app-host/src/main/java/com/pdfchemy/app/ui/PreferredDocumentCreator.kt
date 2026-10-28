package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import com.pdfchemy.app.logic.DestinationProvenance
import com.pdfchemy.app.logic.OutputDestination
import com.pdfchemy.app.logic.OutputPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentCreateLauncher(private val action: (String) -> Unit) {
    fun launch(name: String) = action(name)
}

/** Output creator tracking explicit provenance: fresh copy vs existing/unknown destination. */
@Composable
fun rememberPreferredDocumentDestinationCreator(
    mimeType: String,
    onResult: (OutputDestination?) -> Unit
): DocumentCreateLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentResult by rememberUpdatedState(onResult)
    val prefs = remember { context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE) }
    var requestedName by remember { mutableStateOf<String?>(null) }
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var editedName by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var pickerName by remember { mutableStateOf("Document.pdf") }
    // ACTION_CREATE_DOCUMENT can return an existing document. A folder grant
    // lets this host create and prove ownership of a separate, uniquely named copy.
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) {
            working = false
            currentResult(null)
        } else scope.launch {
            var created: DocumentFile? = null
            var handedOff = false
            try {
                val uri = withContext(Dispatchers.IO) {
                    OutputPolicy.createCopy(checkNotNull(DocumentFile.fromTreeUri(context, tree)), mimeType, pickerName)
                        .also { created = it }.uri
                }
                currentResult(OutputDestination(uri, DestinationProvenance.FRESH_COPY))
                handedOff = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context, "Unable to create output copy in this folder", Toast.LENGTH_LONG).show()
                currentResult(null)
            } finally {
                if (!handedOff) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    created?.let { OutputPolicy.deleteIfFresh(context, it.uri) }
                }
                working = false
            }
        }
    }
    fun chooseFolder(name: String) {
        pickerName = name
        working = true
        systemPicker.launch(null)
    }
    if (requestedName != null) {
        AlertDialog(
            onDismissRequest = { if (!working) { requestedName = null; currentResult(null) } },
            title = { Text("Save a copy") },
            text = {
                Column {
                    Text("Use your saved output folder. Existing files are kept; matching names get a numbered copy.")
                    OutlinedTextField(editedName, { editedName = it }, label = { Text("File name") }, singleLine = true, enabled = !working)
                    if (working) LinearProgressIndicator()
                }
            },
            confirmButton = {
                TextButton(enabled = !working && editedName.isNotBlank(), onClick = {
                    working = true
                    scope.launch {
                        val uri = folderUri
                        val name = OutputPolicy.safeName(editedName)
                        var created: DocumentFile? = null
                        var handedOff = false
                        try {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    OutputPolicy.createCopy(checkNotNull(DocumentFile.fromTreeUri(context, checkNotNull(uri))), mimeType, name)
                                        .also { created = it }.uri
                                }
                            }
                            working = false; requestedName = null
                            if (result.isSuccess) {
                                currentResult(OutputDestination(result.getOrThrow(), DestinationProvenance.FRESH_COPY))
                                handedOff = true
                            } else {
                                result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                                Toast.makeText(context, "Saved folder unavailable. Choose a location to save this copy.", Toast.LENGTH_LONG).show()
                                chooseFolder(name)
                            }
                        } finally {
                            // Only a fresh copy created here is ours to remove before callback ownership.
                            if (!handedOff) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                created?.let {
                                    OutputPolicy.unregisterFreshCopy(it.uri)
                                    runCatching { it.delete() }
                                }
                            }
                        }
                    }
                }) { Text("Save copy") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { val name = OutputPolicy.safeName(editedName); requestedName = null; chooseFolder(name) }) { Text("Choose folder") }
            }
        )
    }
    return DocumentCreateLauncher { name ->
        if (requestedName == null && !working) {
            val stored = prefs.getString(OutputPolicy.DIRECTORY_KEY, null)
            if (stored == null) chooseFolder(OutputPolicy.safeName(name))
            else { folderUri = Uri.parse(stored); editedName = OutputPolicy.safeName(name); requestedName = editedName }
        }
    }
}

/** Existing tool callbacks own writing/cleanup; delegates to destination creator preserving proven freshness. */
@Composable
fun rememberPreferredDocumentCreator(mimeType: String, onResult: (Uri?) -> Unit): DocumentCreateLauncher =
    rememberPreferredDocumentDestinationCreator(mimeType) { onResult(it?.uri) }
