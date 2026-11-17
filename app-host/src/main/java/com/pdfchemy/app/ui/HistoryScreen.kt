package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: MainViewModel, onNavigate: (Screen) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val enabled by viewModel.isHistoryEnabled.collectAsState()
    val items by viewModel.historyList.collectAsState()
    var availability by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var showClearDialog by remember { mutableStateOf(false) }
    val picker = rememberVanguardPdfPicker(
        onNavigateToUnlock = { onNavigate(Screen.UnlockPdf(it)) },
        onNavigateToRepair = { damagedUri ->
            viewModel.setContinuityUri(damagedUri)
            onNavigate(Screen.RepairPdf)
        },
        onPdfSelected = { onNavigate(Screen.PdfReader(it)) }
    )
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_history)) },
            text = { Text(stringResource(R.string.are_you_sure_you_want_to_clear)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearHistory()
                    showClearDialog = false
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    LaunchedEffect(items) {
        availability = withContext(Dispatchers.IO) { items.associate { item ->
            val uri = Uri.parse(item.uriString)
            item.uriString to runCatching {
                if (item.isDirectory) androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.exists() == true
                else context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
            }.getOrDefault(false)
        } }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.history_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.desc_back)) }
            },
            actions = {
                if (enabled && items.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.clear_history))
                    }
                }
            }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (!enabled) {
                Text(stringResource(R.string.history_disabled_notice))
                TextButton(onClick = { onNavigate(Screen.Settings) }) { Text(stringResource(R.string.settings_title)) }
            } else if (items.isEmpty()) {
                Text(stringResource(R.string.history_empty_notice))
                TextButton(onClick = { picker.launch() }) { Text(stringResource(R.string.menu_read_pdf)) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.uriString }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(item.action, style = MaterialTheme.typography.bodySmall)
                                Text(when (availability[item.uriString]) {
                                    true -> if (item.isDirectory) stringResource(R.string.history_item_folder) else item.mimeType
                                    false -> stringResource(R.string.history_item_unavailable)
                                    null -> stringResource(R.string.history_checking_access)
                                })
                                Row {
                                    TextButton(enabled = availability[item.uriString] == true, onClick = {
                                        val uri = Uri.parse(item.uriString)
                                        when {
                                            !item.isDirectory && item.mimeType == "application/pdf" -> onNavigate(Screen.PdfReader(uri))
                                            !item.isDirectory && item.mimeType == "application/epub+zip" -> onNavigate(Screen.ReflowReader(uri))
                                            else -> runCatching { com.pdfchemy.app.logic.DocumentActions.view(context, uri) }
                                                .onFailure { viewModel.notifyError(context.getString(R.string.error_no_app_open_item)) }
                                        }
                                    }) { Text(stringResource(R.string.action_open)) }
                                    if (!item.isDirectory) {
                                        TextButton(
                                            enabled = availability[item.uriString] == true,
                                            onClick = {
                                                val uri = Uri.parse(item.uriString)
                                                runCatching {
                                                    com.pdfchemy.app.logic.ShareUtil.shareFiles(
                                                        context,
                                                        listOf(uri),
                                                        context.getString(R.string.desc_share_output)
                                                    )
                                                }.onFailure {
                                                    viewModel.notifyError(context.getString(R.string.error_no_sharing_app))
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.share))
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { viewModel.removeHistory(item.uriString) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.history_remove_entry_desc, item.name)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
