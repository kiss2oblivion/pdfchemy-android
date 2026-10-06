package com.pdfchemy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.Screen

data class ToolEntry(
    val nameRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val screen: Screen,
    val aliases: List<String> = emptyList()
)

object ToolRegistry {
    val allTools = listOf(
        // Compression
        ToolEntry(R.string.menu_compress, R.string.menu_compress_desc, Icons.Rounded.Compress, Screen.CompressPdf, listOf("shrink", "reduce size", "make smaller", "optimize")),
        
        // Page Studio
        ToolEntry(R.string.menu_merge, R.string.menu_merge_desc, Icons.Rounded.MergeType, Screen.MergePdf, listOf("combine", "join", "append", "add together")),
        ToolEntry(R.string.menu_split, R.string.menu_split_desc, Icons.Rounded.CallSplit, Screen.SplitPdf, listOf("separate", "divide", "extract pages", "break apart")),
        ToolEntry(R.string.menu_delete_pages, R.string.menu_delete_pages_desc, Icons.Rounded.Delete, Screen.DeletePages, listOf("remove pages", "trash pages")),
        ToolEntry(R.string.menu_rotate_pages, R.string.menu_rotate_pages_desc, Icons.Rounded.RotateRight, Screen.RotatePdf, listOf("turn pages", "orientation", "flip")),
        
        // Creation & Conversion
        ToolEntry(R.string.images_to_pdf, R.string.subtitle_images_to_pdf, Icons.Rounded.Image, Screen.ImagesToPdf, listOf("jpg to pdf", "png to pdf", "photo to pdf", "picture to pdf")),
        ToolEntry(R.string.menu_extract_images, R.string.menu_extract_images_desc, Icons.Rounded.ImageSearch, Screen.ExtractImages, listOf("pdf to jpg", "pdf to png", "export images")),
        ToolEntry(R.string.menu_scan, R.string.menu_scan_desc, Icons.Rounded.DocumentScanner, Screen.ScanPdf, listOf("camera to pdf", "digitize", "photo scan")),
        ToolEntry(R.string.text_to_pdf, R.string.subtitle_text_to_pdf, Icons.Rounded.TextFields, Screen.TextToPdf, listOf("txt to pdf", "notes to pdf")),
        ToolEntry(R.string.menu_extract, R.string.menu_extract_desc, Icons.Rounded.FindInPage, Screen.OcrPdf, listOf("ocr", "read text", "text recognition", "pdf to text", "extract text")),
        
        // Security & Metadata
        ToolEntry(R.string.menu_inspect_metadata, R.string.menu_inspect_metadata_desc, Icons.Rounded.Info, Screen.InspectMetadata, listOf("properties", "author", "title", "document info")),
        ToolEntry(R.string.menu_remove_metadata, R.string.menu_remove_metadata_desc, Icons.Rounded.CleaningServices, Screen.StripMetadata, listOf("scrub meta", "remove properties", "clean author", "sanitize metadata"))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolSearchBar(
    modifier: Modifier = Modifier,
    onToolSelected: (Screen) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(false) }

    // It's tricky to filter by stringResource outside a @Composable context (e.g. in remember),
    // but we can look it up efficiently during composition.
    val toolsWithStrings = ToolRegistry.allTools.map { tool ->
        val name = stringResource(id = tool.nameRes).lowercase()
        val desc = stringResource(id = tool.descriptionRes).lowercase()
        tool to (name + " " + desc + " " + tool.aliases.joinToString(" ").lowercase())
    }

    SearchBar(
        query = query,
        onQueryChange = { query = it },
        onSearch = { active = false },
        active = active,
        onActiveChange = { active = it },
        placeholder = { Text("Search tools (e.g. 'merge', 'shrink')") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
        trailingIcon = {
            if (active) {
                IconButton(onClick = {
                    if (query.isNotEmpty()) {
                        query = ""
                    } else {
                        active = false
                    }
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val q = query.trim().lowercase()
            val filteredTools = if (q.isEmpty()) emptyList()
            else toolsWithStrings.filter { it.second.contains(q) }.map { it.first }
            
            items(filteredTools) { tool ->
                ListItem(
                    headlineContent = { Text(stringResource(id = tool.nameRes), fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(stringResource(id = tool.descriptionRes)) },
                    leadingContent = { Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            active = false
                            query = ""
                            onToolSelected(tool.screen)
                        }
                )
            }
        }
    }
}
