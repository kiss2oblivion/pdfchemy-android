// =================================================================================================
// [FEATURE: Directory Spotlight Search] (FEATURES_REGISTRY Desktop Edition: Spotlight Search)
// Multi-file directory keyword search modal dialog with folder picker, live progress, and matches.
// =================================================================================================

package com.pdfchemy.desktop.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.desktop.engine.DesktopDirectorySearchEngine
import com.pdfchemy.desktop.engine.DirectorySearchProgress
import com.pdfchemy.desktop.i18n.DesktopLocalization
import com.pdfchemy.desktop.ui.DesktopFileDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-File Local Directory Spotlight Search Dialog.
 * Recursively or flatly scans PDF files in a chosen directory and offers instant page jumps.
 */
@Composable
fun DirectorySpotlightSearchDialog(
    onDismiss: () -> Unit,
    onOpenFileAtPage: (File, Int) -> Unit
) {
    val strings = DesktopLocalization.strings
    val scope = rememberCoroutineScope()

    var selectedDir by remember { mutableStateOf<File?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var recursive by remember { mutableStateOf(true) }

    var isSearching by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<DirectorySearchProgress?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }

    AlertDialog(
        onDismissRequest = {
            if (isSearching) cancelFlag.set(true)
            onDismiss()
        },
        modifier = Modifier.widthIn(min = 680.dp, max = 820.dp).heightIn(min = 520.dp, max = 660.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.spotlightSearchTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = {
                    if (isSearching) cancelFlag.set(true)
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = strings.close)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().height(480.dp).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Folder Selection Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = selectedDir?.absolutePath ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(strings.searchFolderLabel, fontSize = 11.sp) },
                        placeholder = { Text("Select folder with PDF documents...", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) }
                    )
                    Button(
                        onClick = {
                            val dir = DesktopFileDialog.chooseDirectory()
                            if (dir != null) {
                                selectedDir = dir
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text(strings.searchBtnChooseFolder, fontSize = 12.sp)
                    }
                }

                // Query and Start Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(strings.searchKeywordPlaceholder, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    )
                    Button(
                        onClick = {
                            val dir = selectedDir
                            if (dir != null && searchQuery.isNotBlank()) {
                                isSearching = true
                                cancelFlag.set(false)
                                scope.launch(Dispatchers.IO) {
                                    DesktopDirectorySearchEngine.searchDirectory(
                                        directory = dir,
                                        query = searchQuery,
                                        matchCase = matchCase,
                                        recursive = recursive,
                                        cancelFlag = cancelFlag,
                                        onProgress = { p ->
                                            progress = p
                                            if (p.isComplete) isSearching = false
                                        }
                                    )
                                }
                            }
                        },
                        enabled = selectedDir != null && searchQuery.isNotBlank() && !isSearching,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(52.dp)
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(strings.btnStartSearch, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    if (isSearching) {
                        OutlinedButton(
                            onClick = { cancelFlag.set(true); isSearching = false },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text(strings.btnCancelSearch, fontSize = 12.sp)
                        }
                    }
                }

                // Options: Match case & Subfolders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { matchCase = !matchCase }
                    ) {
                        Checkbox(checked = matchCase, onCheckedChange = { matchCase = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.searchMatchCase, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { recursive = !recursive }
                    ) {
                        Checkbox(checked = recursive, onCheckedChange = { recursive = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.searchIncludeSubfolders, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    progress?.let { p ->
                        Text(
                            text = String.format(strings.searchFilesScanned, p.filesScanned, p.totalFiles),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Results list
                val searchResults = progress?.results ?: emptyList()
                if (searchResults.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSearching) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator()
                                Text("Searching files in directory...", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else if (progress != null) {
                            Text(strings.searchNoMatches, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Select a directory and enter a keyword to begin searching.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    val totalMatchCount = searchResults.sumOf { it.totalMatches }
                    Text(
                        text = String.format(strings.searchMatchesFound, totalMatchCount, searchResults.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        searchResults.forEach { res ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                            Text(res.file.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                "${res.totalMatches} matches",
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Text(
                                        res.file.parentFile?.absolutePath ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    // Snippets
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        res.snippets.forEach { snippet ->
                                            Surface(
                                                onClick = {
                                                    onOpenFileAtPage(res.file, snippet.pageNumber - 1)
                                                    onDismiss()
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                    ) {
                                                        Text(
                                                            "P.${snippet.pageNumber}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                    Text(
                                                        snippet.lineSnippet,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Icon(
                                                        Icons.AutoMirrored.Rounded.ArrowForward,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
