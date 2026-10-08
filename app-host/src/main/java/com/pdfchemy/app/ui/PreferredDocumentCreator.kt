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
import com.pdfchemy.app.logic.OutputPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentCreateLauncher(private val action: (String) -> Unit) {
    fun launch(name: String) = action(name)
}

/** Existing tool callbacks own writing/cleanup; this only chooses a fresh output URI. */
@Composable
fun rememberPreferredDocumentCreator(mimeType: String, onResult: (Uri?) -> Unit): DocumentCreateLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentResult by rememberUpdatedState(onResult)
    val prefs = remember { context.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE) }
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { currentResult(it) }
    var requestedName by remember { mutableStateOf<String?>(null) }
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var editedName by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
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
                                handedOff = true
                                currentResult(result.getOrThrow())
                            } else {
                                Toast.makeText(context, "Saved folder unavailable. Choose a location to save this copy.", Toast.LENGTH_LONG).show()
                                systemPicker.launch(name)
                            }
                        } finally {
                            // Only a fresh copy created here is ours to remove before callback ownership.
                            if (!handedOff) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                created?.let { runCatching { it.delete() } }
                            }
                        }
                    }
                }) { Text("Save copy") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { val name = OutputPolicy.safeName(editedName); requestedName = null; systemPicker.launch(name) }) { Text("Choose location") }
            }
        )
    }
    return DocumentCreateLauncher { name ->
        if (requestedName == null && !working) {
            val stored = prefs.getString(OutputPolicy.DIRECTORY_KEY, null)
            if (stored == null) systemPicker.launch(OutputPolicy.safeName(name))
            else { folderUri = Uri.parse(stored); editedName = OutputPolicy.safeName(name); requestedName = editedName }
        }
    }
}
