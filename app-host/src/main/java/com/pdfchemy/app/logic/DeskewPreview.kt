package com.pdfchemy.app.logic

import android.graphics.Bitmap
import android.net.Uri
import android.content.Context
import com.pdfchemy.app.utils.ResourceTransferOwner
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DeskewPreviewResult(val bitmap: Bitmap, val angle: Float)

/** Transfers bitmap ownership on return; the producer cleans its resources before throwing. */
internal fun interface DeskewPreviewLoader {
    suspend fun load(uri: Uri): DeskewPreviewResult?
}

internal fun defaultDeskewPreviewLoader(context: Context) = DeskewPreviewLoader { uri ->
    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderPageToBitmap(context, descriptor, 0, 360)?.let { bitmap ->
            try { DeskewPreviewResult(bitmap, PdfDeskewEngine.detectSkewAngle(bitmap)) }
            catch (error: Throwable) { if (!bitmap.isRecycled) bitmap.recycle(); throw error }
        }
    }
}

internal data class DeskewPreviewState(
    val uri: Uri? = null,
    val bitmap: Bitmap? = null,
    val angle: Float = 0f,
    val loading: Boolean = false,
    val failed: Boolean = false
)

/** Main-thread request ownership; unpublished bitmaps remain owned across IO handoff/cancellation. */
internal class DeskewPreviewOwner(private val scope: CoroutineScope, private val loader: DeskewPreviewLoader) {
    private val mutableState = MutableStateFlow(DeskewPreviewState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var request: Job? = null
    private var disposed = false

    private fun retireCurrent() {
        mutableState.value.bitmap?.let { if (!it.isRecycled) it.recycle() }
    }

    fun analyze(uri: Uri) {
        check(!disposed)
        val id = ++generation
        request?.cancel()
        retireCurrent()
        mutableState.value = DeskewPreviewState(uri = uri, loading = true)
        request = scope.launch(Dispatchers.Main.immediate) {
            try {
                ResourceTransferOwner<DeskewPreviewResult> { if (!it.bitmap.isRecycled) it.bitmap.recycle() }.use { owner ->
                    val result = withContext(Dispatchers.IO) {
                        checkNotNull(loader.load(uri)) { "No readable preview" }.also(owner::stage)
                    }
                    currentCoroutineContext().ensureActive()
                    require(result.angle.isFinite() && !result.bitmap.isRecycled) { "Invalid preview result" }
                    if (!disposed && id == generation) {
                        checkNotNull(owner.commit())
                        mutableState.value = DeskewPreviewState(uri, result.bitmap, result.angle)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!disposed && id == generation) {
                    mutableState.value = DeskewPreviewState(uri = uri, failed = true)
                    AppLogger.e("Failed to analyze PDF for deskew", error)
                }
            } finally {
                if (!disposed && id == generation && mutableState.value.loading) {
                    mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
    }

    fun clear() {
        ++generation
        request?.cancel(); request = null
        retireCurrent()
        mutableState.value = DeskewPreviewState()
    }

    fun close() {
        if (!disposed) { disposed = true; clear() }
    }
}
