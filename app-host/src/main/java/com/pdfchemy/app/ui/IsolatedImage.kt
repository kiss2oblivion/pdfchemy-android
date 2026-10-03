package com.pdfchemy.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.pdfchemy.app.logic.IsolatedImageDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Displays only bounded raw pixels returned by the isolated decoder. */
@Composable
fun IsolatedImage(model: Uri, contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val context = LocalContext.current
    var bitmap by remember(model) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(model) {
        for (attempt in 0 until 12) {
            try { bitmap = IsolatedImageDecoder.decode(context, model); break }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error.message?.contains("BUSY") != true) break
                delay(100L * (attempt + 1))
            }
        }
    }
    DisposableEffect(bitmap) { val owned = bitmap; onDispose { owned?.recycle() } }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription, modifier, contentScale = contentScale) }
}
