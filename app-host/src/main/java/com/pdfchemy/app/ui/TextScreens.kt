package com.pdfchemy.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException
import androidx.compose.ui.res.stringResource
import com.pdfchemy.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextCleanerScreen(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    
    var textContent by remember { mutableStateOf("") }
    
    val wordCount = if (textContent.isBlank()) 0 else textContent.trim().split("\\s+".toRegex()).size
    val charCount = textContent.length
    val lineCount = if (textContent.isEmpty()) 0 else textContent.lines().size

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
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val pasted = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!pasted.isNullOrEmpty()) {
                                textContent = if (textContent.isEmpty()) pasted else "$textContent\n$pasted"
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste")
                    }
                    if (textContent.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Cleaned Text", textContent))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy")
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
                    Text("Lines: $lineCount", style = MaterialTheme.typography.labelLarge)
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
                            Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: JSONException) {
                        Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: JSONException) {
                        Toast.makeText(context, "Invalid JSON format", Toast.LENGTH_SHORT).show()
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
