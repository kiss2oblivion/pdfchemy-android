package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.pdfchemy.app.logic.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every posture uses the same unrotated coordinate plane as isolated export. */
@Composable
internal fun AnnotationPageCanvas(bitmap: Bitmap, modification: PageModification, tool: EditorTool,
    color: Color, strokeWidth: Float, onDrawing: (DrawingPath) -> Unit, onText: (Offset) -> Unit,
    onStamp: (Offset) -> Unit, onRedaction: (RedactionBox) -> Unit) {
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var points by remember { mutableStateOf<List<DrawingPoint>>(emptyList()) }
    var redactStart by remember { mutableStateOf<Offset?>(null) }
    var redactEnd by remember { mutableStateOf<Offset?>(null) }
    var overlay by remember { mutableStateOf<Bitmap?>(null) }
    val draft = if (points.size > 1) DrawingPath(points = points, color = color.toArgb(),
        strokeWidth = if (tool == EditorTool.HIGHLIGHTER) strokeWidth * 2.5f else strokeWidth,
        isHighlighter = tool == EditorTool.HIGHLIGHTER) else null
    val preview = modification.copy(drawings = modification.drawings + listOfNotNull(draft),
        redactions = modification.redactions + listOfNotNull(if (redactStart != null && redactEnd != null)
            RedactionBox(modification.pageIndex, RectF(minOf(redactStart!!.x, redactEnd!!.x), minOf(redactStart!!.y, redactEnd!!.y),
                maxOf(redactStart!!.x, redactEnd!!.x), maxOf(redactStart!!.y, redactEnd!!.y))) else null))
    LaunchedEffect(preview, bitmap) {
        var rendered: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { rendered = AnnotationRenderer.render(preview, bitmap.width, bitmap.height) }
            overlay = rendered
            rendered = null
        } finally { rendered?.recycle() }
    }
    DisposableEffect(overlay) { val owned = overlay; onDispose { owned?.recycle() } }
    fun normalized(point: Offset): Offset = Offset((point.x / bounds.width.coerceAtLeast(1)).coerceIn(0f, 1f),
        (point.y / bounds.height.coerceAtLeast(1)).coerceIn(0f, 1f))
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val sideways = modification.rotationDegrees % 180 != 0
        val pageWidth = if (sideways) minOf(maxHeight, maxWidth * ratio) else minOf(maxWidth, maxHeight * ratio)
        Box(Modifier.requiredSize(pageWidth, pageWidth / ratio).rotate(modification.rotationDegrees.toFloat())
            .background(Color.White).onSizeChanged { bounds = it }
            .pointerInput(tool, modification.pageIndex, color, strokeWidth) {
                when (tool) {
                    EditorTool.TEXT, EditorTool.STAMP -> detectTapGestures { point ->
                        if (tool == EditorTool.TEXT) onText(normalized(point)) else onStamp(normalized(point))
                    }
                    EditorTool.PEN, EditorTool.HIGHLIGHTER, EditorTool.REDACT -> detectDragGestures(
                        onDragStart = { point ->
                            val start = normalized(point)
                            if (tool == EditorTool.REDACT) { redactStart = start; redactEnd = start }
                            else points = listOf(DrawingPoint(start.x, start.y))
                        },
                        onDrag = { change, _ ->
                            change.consume(); val point = normalized(change.position)
                            if (tool == EditorTool.REDACT) redactEnd = point
                            else points = points + DrawingPoint(point.x, point.y)
                        },
                        onDragCancel = { points = emptyList(); redactStart = null; redactEnd = null },
                        onDragEnd = {
                            if (tool == EditorTool.REDACT) {
                                val start = redactStart; val end = redactEnd
                                if (start != null && end != null && kotlin.math.abs(start.x - end.x) > .02f && kotlin.math.abs(start.y - end.y) > .01f)
                                    onRedaction(RedactionBox(modification.pageIndex, RectF(minOf(start.x,end.x),minOf(start.y,end.y),maxOf(start.x,end.x),maxOf(start.y,end.y))))
                            } else if (points.size > 1) onDrawing(DrawingPath(points = points, color = color.toArgb(),
                                strokeWidth = if (tool == EditorTool.HIGHLIGHTER) strokeWidth * 2.5f else strokeWidth, isHighlighter = tool == EditorTool.HIGHLIGHTER))
                            points = emptyList(); redactStart = null; redactEnd = null
                        })
                    EditorTool.VIEW -> Unit
                }
            }) {
            Image(bitmap.asImageBitmap(), "Page ${modification.pageIndex + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            overlay?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        }
    }
}
