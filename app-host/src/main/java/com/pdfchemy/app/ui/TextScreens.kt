package com.pdfchemy.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.JailEngineBridge
import com.pdfchemy.app.logic.TextPagesContract
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextCleanerScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var textContent by remember { mutableStateOf("") }

    val wordCount = if (textContent.isBlank()) 0 else textContent.trim().split("\\s+".toRegex()).size
    val charCount = textContent.length
    val lineCount = if (textContent.isEmpty()) 0 else textContent.lines().size

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: ""
                val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
                        FileUtils.getFileName(context, uri)?.endsWith(".pdf", ignoreCase = true) == true
                val loadedText = if (isPdf) {
                    try {
                        val contract = JailEngineBridge.callTyped<TextPagesContract>(context, "TEXT_PAGES", uri, null)
                        contract.pages.joinToString("\n\n")
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    try {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                }
                withContext(Dispatchers.Main) {
                    if (loadedText.isNotEmpty()) {
                        textContent = loadedText
                    }
                }
            }
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { destUri ->
        if (destUri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(destUri)?.use { out ->
                        out.write(textContent.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.msg_text_saved_success), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.text_cleaner)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = { openFileLauncher.launch(arrayOf("text/plain", "text/*", "application/pdf", "application/json")) }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.desc_open_file))
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val pasted = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!pasted.isNullOrEmpty()) {
                                textContent = if (textContent.isEmpty()) pasted else "$textContent\n$pasted"
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.desc_paste))
                    }
                    if (textContent.isNotBlank()) {
                        IconButton(onClick = { saveFileLauncher.launch("cleaned_text.txt") }) {
                            Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.desc_save_file))
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Cleaned Text", textContent))
                            Toast.makeText(context, context.getString(R.string.msg_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.desc_copy_clipboard))
                        }
                        IconButton(onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, textContent)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.desc_share_text))
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.label_words, wordCount), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.label_lines_count, lineCount), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.label_chars, charCount), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Action Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionChip(stringResource(R.string.btn_trim_spaces)) { textContent = textContent.trim() }
                ActionChip(stringResource(R.string.btn_normalize_spaces)) { textContent = textContent.replace("\\s+".toRegex(), " ") }
                ActionChip(stringResource(R.string.btn_remove_empty_lines)) { 
                    textContent = textContent.lineSequence().filter { it.isNotBlank() }.joinToString("\n") 
                }
                ActionChip(stringResource(R.string.btn_remove_duplicates)) { 
                    textContent = textContent.lineSequence().distinct().joinToString("\n") 
                }
                ActionChip(stringResource(R.string.btn_sort_lines)) { 
                    textContent = textContent.lineSequence().sorted().joinToString("\n") 
                }
                ActionChip(stringResource(R.string.btn_format_json)) {
                    try {
                        val trimmed = textContent.trim()
                        if (trimmed.startsWith("[")) {
                            textContent = JSONArray(trimmed).toString(4)
                        } else if (trimmed.startsWith("{")) {
                            textContent = JSONObject(trimmed).toString(4)
                        } else {
                            Toast.makeText(context, context.getString(R.string.error_invalid_json_format), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: JSONException) {
                        Toast.makeText(context, context.getString(R.string.error_invalid_json_format), Toast.LENGTH_SHORT).show()
                    }
                }
                ActionChip(stringResource(R.string.btn_minify_json)) {
                    try {
                        val trimmed = textContent.trim()
                        if (trimmed.startsWith("[")) {
                            textContent = JSONArray(trimmed).toString()
                        } else if (trimmed.startsWith("{")) {
                            textContent = JSONObject(trimmed).toString()
                        } else {
                            Toast.makeText(context, context.getString(R.string.error_invalid_json_format), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: JSONException) {
                        Toast.makeText(context, context.getString(R.string.error_invalid_json_format), Toast.LENGTH_SHORT).show()
                    }
                }
                ActionChip(stringResource(R.string.btn_clear)) { textContent = "" }
            }

            // Text Input Area
            OutlinedTextField(
                value = textContent,
                onValueChange = { textContent = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.paste_your_text_here)) },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                shape = MaterialTheme.shapes.medium
            )
        }
    }
}

@Composable
fun ActionChip(label: String, onClick: () -> Unit) {
    ElevatedFilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label) }
    )
}
