package com.pdfchemy.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.pdfchemy.app.logic.FileUtil
import com.pdfchemy.app.logic.JailEngineBridge
import com.pdfchemy.app.logic.LatestRequest
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.TextOutputPublisher
import com.pdfchemy.app.logic.TextPagesContract
import com.pdfchemy.app.security.SecurityLimits
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal class OversizedTextInputException : IllegalArgumentException("Text quota exceeded")

private inline fun readUtf8WithinQuota(
    input: InputStream,
    maxBytes: Int,
    requireProgress: Boolean = false,
    onStep: () -> Unit = {}
): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        onStep()
        val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) break
        if (count == 0) {
            if (requireProgress) throw java.io.IOException("Text provider made no progress")
            continue
        }
        if (output.size() + count > maxBytes) throw OversizedTextInputException()
        output.write(buffer, 0, count)
    }
    onStep()
    return output.toString(Charsets.UTF_8.name()).also { SecurityLimits.enforceStringLength(it) }
}

internal fun readBoundedUtf8Text(input: InputStream, maxBytes: Int = SecurityLimits.MAX_TEXT_BYTES): String =
    readUtf8WithinQuota(input, maxBytes)

/** Read at most the text quota plus one detection byte, including non-seekable providers. */
internal suspend fun readCleanerText(input: InputStream): String {
    val ctx = currentCoroutineContext()
    return readUtf8WithinQuota(input, SecurityLimits.MAX_TEXT_BYTES, requireProgress = true) {
        ctx.ensureActive()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextCleanerScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var textContent by remember { mutableStateOf("") }
    var lastSourceUri by remember { mutableStateOf<Uri?>(null) }
    val imports = remember { LatestRequest() }
    var pendingSave by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var isTransforming by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { imports.invalidate() } }

    fun transformJsonAsync(pretty: Boolean) {
        if (isTransforming) return
        val snapshot = textContent
        val ticket = imports.begin()
        isTransforming = true
        coroutineScope.launch {
            try {
                val transformed = withContext(Dispatchers.Default) {
                    val trimmed = snapshot.trim()
                    val result = when {
                        trimmed.startsWith("[") -> if (pretty) JSONArray(trimmed).toString(4) else JSONArray(trimmed).toString()
                        trimmed.startsWith("{") -> if (pretty) JSONObject(trimmed).toString(4) else JSONObject(trimmed).toString()
                        else -> null
                    } ?: return@withContext null
                    SecurityLimits.enforceStringLength(result)
                    result
                }
                if (imports.isCurrent(ticket)) {
                    if (transformed != null) textContent = transformed
                    else Toast.makeText(context, context.getString(R.string.error_invalid_json_format), Toast.LENGTH_SHORT).show()
                }
            } catch (_: JSONException) {
                if (imports.isCurrent(ticket)) {
                    Toast.makeText(context, context.getString(R.string.error_invalid_json_format), Toast.LENGTH_SHORT).show()
                }
            } catch (_: IllegalArgumentException) {
                if (imports.isCurrent(ticket)) {
                    val maxMb = (SecurityLimits.MAX_TEXT_BYTES / (1024 * 1024)).coerceAtLeast(1)
                    Toast.makeText(context, context.getString(R.string.error_oversized_text_file, maxMb), Toast.LENGTH_SHORT).show()
                }
            } finally {
                isTransforming = false
            }
        }
    }

    val wordCount = if (textContent.isBlank()) 0 else textContent.trim().split("\\s+".toRegex()).size
    val charCount = textContent.length
    val lineCount = if (textContent.isEmpty()) 0 else textContent.lines().size

    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ticket = imports.begin()
            coroutineScope.launch {
                try {
                    val loadedText = withContext(Dispatchers.IO) {
                        val contentResolver = context.contentResolver
                        val mimeType = contentResolver.getType(uri) ?: ""
                        val isPdf = mimeType.equals("application/pdf", ignoreCase = true) ||
                            FileUtils.getFileName(context, uri)?.endsWith(".pdf", ignoreCase = true) == true
                        if (isPdf) {
                            JailEngineBridge.callTyped<TextPagesContract>(context, "TEXT_PAGES", uri, null)
                                .pages.joinToString("\n\n").also { SecurityLimits.enforceStringLength(it) }
                        } else {
                            contentResolver.openInputStream(uri)?.use { readCleanerText(it) }
                                ?: throw java.io.IOException("Unable to open text document")
                        }
                    }
                    if (imports.isCurrent(ticket)) {
                        lastSourceUri = uri
                        textContent = loadedText
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: OversizedTextInputException) {
                    if (imports.isCurrent(ticket)) {
                        val maxMb = (SecurityLimits.MAX_TEXT_BYTES / (1024 * 1024)).coerceAtLeast(1)
                        Toast.makeText(context, context.getString(R.string.error_oversized_text_file, maxMb), Toast.LENGTH_LONG).show()
                    }
                } catch (_: Exception) {
                    if (imports.isCurrent(ticket)) {
                        Toast.makeText(context, context.getString(R.string.error_open_text_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val saveFileLauncher = rememberPreferredDocumentDestinationCreator("text/plain") { destination ->
        val snapshot = pendingSave
        pendingSave = null
        if (destination != null) {
            saving = true
            coroutineScope.launch {
                try {
                    requireNotNull(snapshot) { "No text was selected for saving" }
                    TextOutputPublisher.publish(context, destination.uri) { snapshot }
                    Toast.makeText(context, context.getString(R.string.msg_text_saved_success), Toast.LENGTH_SHORT).show()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    Toast.makeText(context, context.getString(R.string.error_save_text_failed), Toast.LENGTH_LONG).show()
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        OutputPolicy.deleteIfFresh(context, destination.uri)
                    }
                    saving = false
                }
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
                                val updated = if (textContent.isEmpty()) pasted else "$textContent\n$pasted"
                                if (updated.length <= SecurityLimits.MAX_TEXT_BYTES && updated.toByteArray(Charsets.UTF_8).size <= SecurityLimits.MAX_TEXT_BYTES) {
                                    imports.invalidate()
                                    textContent = updated
                                } else {
                                    val maxMb = (SecurityLimits.MAX_TEXT_BYTES / (1024 * 1024)).coerceAtLeast(1)
                                    Toast.makeText(context, context.getString(R.string.error_oversized_text_file, maxMb), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.desc_paste))
                    }
                    if (textContent.isNotBlank()) {
                        IconButton(
                            enabled = !saving && pendingSave == null,
                            onClick = {
                                val suggestedName = lastSourceUri?.let {
                                    FileUtil.generateSuggestedName(context, it, "cleaned", extension = "txt")
                                } ?: "cleaned_text.txt"
                                pendingSave = textContent
                                saveFileLauncher.launch(suggestedName)
                            }
                        ) {
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
                ActionChip(stringResource(R.string.btn_format_json)) { transformJsonAsync(pretty = true) }
                ActionChip(stringResource(R.string.btn_minify_json)) { transformJsonAsync(pretty = false) }
                ActionChip(stringResource(R.string.btn_clear)) {
                    imports.invalidate()
                    lastSourceUri = null
                    textContent = ""
                }
            }

            // Text Input Area
            OutlinedTextField(
                value = textContent,
                onValueChange = { value ->
                    if (value.length <= SecurityLimits.MAX_TEXT_BYTES && value.toByteArray(Charsets.UTF_8).size <= SecurityLimits.MAX_TEXT_BYTES) {
                        imports.invalidate()
                        textContent = value
                    }
                },
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
