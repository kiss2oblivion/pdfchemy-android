package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.net.Uri
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.PageAction
import com.pdfchemy.app.logic.PdfPageOrganizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.UUID

data class OrganizerPageItem(
    val id: String = UUID.randomUUID().toString(),
    val originalIndex: Int? = null,
    val rotation: Int = 0,
    val isBlank: Boolean = false,
    val thumbnail: Bitmap? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageOrganizerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var pageItems by remember { mutableStateOf<List<OrganizerPageItem>>(emptyList()) }
    var originalPageItems by remember { mutableStateOf<List<OrganizerPageItem>>(emptyList()) }
    var selectedItemIndex by remember { mutableIntStateOf(-1) }
    var isOrganizing by remember { mutableStateOf(false) }

    val currentItems by rememberUpdatedState(pageItems)
    DisposableEffect(Unit) {
        onDispose {
            currentItems.forEach { it.thumbnail?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() } }
        }
    }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        coroutineScope.launch(Dispatchers.IO) {
                var pfd: ParcelFileDescriptor? = null
                try {
                    // Clean up existing thumbnails
                    withContext(Dispatchers.Main) {
                        pageItems.forEach { it.thumbnail?.let { bmp -> if (!bmp.isRecycled) bmp.recycle() } }
                        pageItems = emptyList()
                        originalPageItems = emptyList()
                    }

                    pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@launch
                    val count = com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
                    val items = mutableListOf<OrganizerPageItem>()

                    for (i in 0 until count) {
                        coroutineContext.ensureActive()
                        val bmp = com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderPageToBitmap(context, pfd, i, 240)
                        if (bmp != null) {
                            items.add(OrganizerPageItem(originalIndex = i, thumbnail = bmp))
                        }
                    }

                    withContext(Dispatchers.Main) {
                        pageItems = items
                        originalPageItems = items
                        selectedItemIndex = if (items.isNotEmpty()) 0 else -1
                    }
                } catch (e: Exception) {
                    com.pdfchemy.app.utils.AppLogger.e("Failed to load thumbnails for organizer: ${e.message}", e)
                } finally {
                    try { pfd?.close() } catch (_: Throwable) {}
                }
            }
        }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null && pageItems.isNotEmpty()) {
            isOrganizing = true
            coroutineScope.launch(Dispatchers.IO) {
                val actions = pageItems.map {
                    PageAction(
                        originalPageIndex = it.originalIndex,
                        rotationDegrees = it.rotation,
                        isBlank = it.isBlank
                    )
                }
                val success = PdfPageOrganizer.reorganizePages(context, selectedPdfUri!!, destUri, actions)
                withContext(Dispatchers.Main) {
                    isOrganizing = false
                    if (success) {
                        viewModel.notifySuccess(
                            context.getString(R.string.title_organizer_success),
                            context.getString(R.string.desc_organizer_success),
                            destUri
                        )
                        onBack()
                    } else {
                        viewModel.notifyError(context.getString(R.string.error_organizer_failed))
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_page_organizer), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (selectedPdfUri == null) {
                // Empty State
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.ViewModule, contentDescription = null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.organizer_headline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.organizer_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.select_pdf_to_organize),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // Global Quick Action Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            if (pageItems.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                pageItems = pageItems.reversed()
                                if (selectedItemIndex >= 0) {
                                    selectedItemIndex = pageItems.size - 1 - selectedItemIndex
                                }
                            }
                        },
                        label = { Text("Reverse", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Rounded.SyncAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                        }
                    )

                    AssistChip(
                        onClick = {
                            if (pageItems.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                pageItems = pageItems.map { it.copy(rotation = (it.rotation + 90) % 360) }
                            }
                        },
                        label = { Text("Rotate All", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Rounded.RotateRight, contentDescription = null, modifier = Modifier.size(15.dp))
                        }
                    )

                    AssistChip(
                        onClick = {
                            if (originalPageItems.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pageItems = originalPageItems.map { it.copy(rotation = 0) }
                                selectedItemIndex = if (pageItems.isNotEmpty()) 0 else -1
                            }
                        },
                        label = { Text("Reset", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "${pageItems.size} p.",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Grid of Reorderable Page Thumbnails
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(pageItems, key = { _, item -> item.id }) { index, item ->
                        val isSelected = selectedItemIndex == index
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedItemIndex = index
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (item.thumbnail != null && !item.isBlank) {
                                    Image(
                                        bitmap = item.thumbnail.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .rotate(item.rotation.toFloat()),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    // Blank Page indicator
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFFF0F0F0)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(stringResource(R.string.label_blank_page), fontSize = 11.sp, color = Color.Gray)
                                    }
                                }

                                // Page Badge
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text("${index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Page Operation Action Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Move Left / Up
                        IconButton(
                            onClick = {
                                if (selectedItemIndex > 0) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val list = pageItems.toMutableList()
                                    val item = list.removeAt(selectedItemIndex)
                                    list.add(selectedItemIndex - 1, item)
                                    pageItems = list
                                    selectedItemIndex--
                                }
                            },
                            enabled = selectedItemIndex > 0
                        ) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_move_left))
                        }

                        // Rotate 90
                        IconButton(
                            onClick = {
                                if (selectedItemIndex in pageItems.indices) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val list = pageItems.toMutableList()
                                    val item = list[selectedItemIndex]
                                    list[selectedItemIndex] = item.copy(rotation = (item.rotation + 90) % 360)
                                    pageItems = list
                                }
                            },
                            enabled = selectedItemIndex in pageItems.indices
                        ) {
                            Icon(Icons.Rounded.RotateRight, contentDescription = stringResource(R.string.action_rotate_page))
                        }

                        // Duplicate
                        IconButton(
                            onClick = {
                                if (selectedItemIndex in pageItems.indices) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val list = pageItems.toMutableList()
                                    val item = list[selectedItemIndex]
                                    list.add(selectedItemIndex + 1, item.copy(id = UUID.randomUUID().toString()))
                                    pageItems = list
                                    selectedItemIndex++
                                }
                            },
                            enabled = selectedItemIndex in pageItems.indices
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_duplicate_page))
                        }

                        // Insert Blank
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val insertIdx = if (selectedItemIndex in pageItems.indices) selectedItemIndex + 1 else pageItems.size
                                val list = pageItems.toMutableList()
                                list.add(insertIdx, OrganizerPageItem(isBlank = true))
                                pageItems = list
                                selectedItemIndex = insertIdx
                            }
                        ) {
                            Icon(Icons.Rounded.NoteAdd, contentDescription = stringResource(R.string.action_insert_blank_page))
                        }

                        // Delete
                        IconButton(
                            onClick = {
                                if (selectedItemIndex in pageItems.indices && pageItems.size > 1) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val list = pageItems.toMutableList()
                                    list.removeAt(selectedItemIndex)
                                    pageItems = list
                                    selectedItemIndex = selectedItemIndex.coerceAtMost(pageItems.size - 1)
                                }
                            },
                            enabled = selectedItemIndex in pageItems.indices && pageItems.size > 1
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_delete_page), tint = MaterialTheme.colorScheme.error)
                        }

                        // Move Right / Down
                        IconButton(
                            onClick = {
                                if (selectedItemIndex < pageItems.size - 1) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val list = pageItems.toMutableList()
                                    val item = list.removeAt(selectedItemIndex)
                                    list.add(selectedItemIndex + 1, item)
                                    pageItems = list
                                    selectedItemIndex++
                                }
                            },
                            enabled = selectedItemIndex < pageItems.size - 1
                        ) {
                            Icon(Icons.Rounded.ArrowForward, contentDescription = stringResource(R.string.action_move_right))
                        }
                    }
                }

                // Save Reorganized PDF Button
                Button(
                    onClick = { savePdfLauncher.launch("reorganized_document_${System.currentTimeMillis()}.pdf") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = pageItems.isNotEmpty() && !isOrganizing
                ) {
                    if (isOrganizing) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_save_organized_pdf, pageItems.size),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
