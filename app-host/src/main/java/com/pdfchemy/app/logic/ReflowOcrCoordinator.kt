package com.pdfchemy.app.logic

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

internal sealed interface ReflowOcrState {
    object Idle : ReflowOcrState
    data class Processing(val uri: Uri) : ReflowOcrState
    data class Ready(val uri: Uri, val text: String) : ReflowOcrState
    data class Empty(val uri: Uri) : ReflowOcrState
    data class Failed(val uri: Uri) : ReflowOcrState
}

/** Owns OCR results/errors across source replacement, cancellation and composable disposal. */
internal class ReflowOcrCoordinator(private val extract: suspend (Uri) -> Result<String>) : Closeable {
    private val requests = LatestRequest()
    private val mutableState = MutableStateFlow<ReflowOcrState>(ReflowOcrState.Idle)
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var closed = false

    fun request(scope: CoroutineScope, uri: Uri) {
        val (next, previous) = synchronized(this) {
            if (closed) return
            val ticket = requests.begin()
            val previous = job
            mutableState.value = ReflowOcrState.Processing(uri)
            val next = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = extract(uri)
                    currentCoroutineContext().ensureActive()
                    publish(ticket, result.fold(
                        onSuccess = { if (it.isBlank()) ReflowOcrState.Empty(uri) else ReflowOcrState.Ready(uri, it) },
                        onFailure = { if (it is CancellationException) throw it; ReflowOcrState.Failed(uri) }
                    ))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    publish(ticket, ReflowOcrState.Failed(uri))
                }
            }
            job = next
            next.invokeOnCompletion { cause ->
                if (cause is CancellationException) publish(ticket, ReflowOcrState.Idle)
            }
            next to previous
        }
        previous?.cancel()
        next.start()
    }

    @Synchronized private fun publish(ticket: Long, value: ReflowOcrState) {
        if (!closed && requests.isCurrent(ticket)) mutableState.value = value
    }

    fun reset() = clear(close = false)
    override fun close() = clear(close = true)
    private fun clear(close: Boolean) {
        val previous = synchronized(this) {
            closed = closed || close
            requests.invalidate()
            val previous = job; job = null
            mutableState.value = ReflowOcrState.Idle
            previous
        }
        previous?.cancel()
    }
}
