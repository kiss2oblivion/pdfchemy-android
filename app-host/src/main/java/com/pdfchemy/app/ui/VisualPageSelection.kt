package com.pdfchemy.app.ui

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// A preview belongs to a visible composition, never to document/undo history.
private val previewSlots = Semaphore(2)

@Composable
fun PdfPagePreview(uri: Uri, pageIndex: Int, modifier: Modifier = Modifier, rotation: Int = 0,
    targets: List<com.pdfchemy.app.logic.RedactionBox> = emptyList()) {
    val context = LocalContext.current
    var retry by remember(uri, pageIndex) { mutableIntStateOf(0) }
    var bitmap by remember(uri, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri, pageIndex) { mutableStateOf(false) }
    LaunchedEffect(uri, pageIndex, retry) {
        var owned: Bitmap? = null
        failed = false
        try {
            withContext(Dispatchers.IO) {
                owned = previewSlots.withPermit {
                    com.pdfchemy.app.logic.PdfEditor.renderPageBitmap(context, uri, pageIndex, 240)
                }
            }
            bitmap = owned
            failed = owned == null
            kotlinx.coroutines.awaitCancellation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            bitmap = null
            owned?.recycle()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().rotate(rotation.toFloat()).drawWithContent {
                    drawContent()
                    val scale = minOf(size.width / it.width, size.height / it.height)
                    val width = it.width * scale; val height = it.height * scale
                    val x = (size.width - width) / 2; val y = (size.height - height) / 2
                    targets.forEach { target ->
                        val rect = target.normalizedRect
                        drawRect(Color.Red.copy(alpha = .35f), Offset(x + rect.left * width, y + rect.top * height), Size(rect.width() * width, rect.height() * height))
                    }
                }, contentScale = ContentScale.Fit)
        } ?: if (failed) {
            TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
        } else { CircularProgressIndicator(Modifier.size(24.dp)) }
    }
}

@Composable
fun VisualPageSelector(uri: Uri, range: String, onRange: (String) -> Unit) {
    var open by remember(uri) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.select_pages_visually)) }
    if (open) VisualPageDialog(uri, range, { open = false }) { onRange(it); open = false }
}

@Composable
private fun VisualPageDialog(uri: Uri, range: String, dismiss: () -> Unit, apply: (String) -> Unit) {
    val context = LocalContext.current
    var count by remember(uri) { mutableIntStateOf(0) }
    var failed by remember(uri) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var selected by remember(uri) { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(uri, retry) {
        failed = false
        try {
            count = com.pdfchemy.app.logic.PdfEditor.getPageCount(context, uri)
            check(count > 0)
            selected = com.pdfchemy.app.logic.VisualPageRanges.parse(range, count)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.select_pages_visually)) },
        text = {
            if (failed) TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
            else if (count == 0) CircularProgressIndicator()
            else LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxWidth().height(420.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(count, key = { it }) { index ->
                    val page = index + 1
                    OutlinedCard(Modifier.clickable {
                        selected = if (page in selected) selected - page else selected + page
                    }) {
                        PdfPagePreview(uri, index, Modifier.fillMaxWidth().height(150.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(page in selected, onCheckedChange = {
                                selected = if (it) selected + page else selected - page
                            })
                            Text(stringResource(R.string.visual_page_number, page))
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = { apply(com.pdfchemy.app.logic.VisualPageRanges.format(selected)) }) {
                Text(stringResource(R.string.apply))
            }
        }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
