package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import java.io.File
import java.io.OutputStream
import java.util.UUID

// Synchronous staging-dir space estimate on API 24+ without Context/StorageManager coupling in pure-JVM tests.
@android.annotation.SuppressLint("UsableSpace")
private fun defaultAvailableBytes(raw: File): Long = raw.parentFile!!.usableSpace

/** One bounded writer per job. Callback order is serialized without holding the writer state lock during backpressure. */
class PcmWriter(
    private val jobId: UUID,
    private val generation: UUID,
    private val raw: File,
    scope: CoroutineScope,
    private val availableBytes: () -> Long = { defaultAvailableBytes(raw) },
    private val openOutput: () -> OutputStream = { raw.outputStream() },
    private val isJobActive: () -> Boolean = { true }
) {
    private sealed class Message {
        class Data(val bytes: ByteArray) : Message()
        class Barrier(val result: CompletableDeferred<Long>) : Message()
    }
    private data class FailureAction(val result: CompletableDeferred<Long>?, val error: Throwable)
    private val queue = Channel<Message>(64)
    private val callbackLock = Any()
    private var accepting = true
    private var utterance: String? = null
    private var began = false
    private var done = false
    private var chunkBytes = 0L
    private var acceptedBytes = 0L
    private var pending: CompletableDeferred<Long>? = null
    private var failure: Throwable? = null
    private var pcmFormat: PcmFormat? = null
    val format: PcmFormat get() = synchronized(this) { requireNotNull(pcmFormat) { "No PCM format received" } }
    private val writer = scope.launch {
        try {
            openOutput().use { output ->
                for (message in queue) {
                    if (!synchronized(this@PcmWriter) { accepting && isJobActive() }) {
                        invalidate(CancellationException("Audio job is no longer active"))
                        break
                    }
                    when (message) {
                        is Message.Data -> output.write(message.bytes)
                        is Message.Barrier -> {
                            output.flush()
                            val completion = synchronized(this@PcmWriter) {
                                chunkBytes to if (accepting) null else
                                    (failure ?: CancellationException("Export aborted"))
                            }
                            // Completing a deferred can immediately resume service cleanup.
                            // Never run that continuation while holding the state monitor.
                            if (completion.second == null) message.result.complete(completion.first)
                            else message.result.completeExceptionally(requireNotNull(completion.second))
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            invalidate(error)
        }
    }

    @Synchronized fun beginChunk(index: Int): Pair<String, CompletableDeferred<Long>> {
        check(accepting) { "Writer closed" }
        failure?.let { throw it }
        check(pending == null || pending!!.isCompleted) { "Previous chunk has not drained" }
        utterance = "$jobId/$generation/$index"
        began = false; done = false; chunkBytes = 0
        val result = CompletableDeferred<Long>()
        pending = result
        return utterance!! to result
    }

    private fun matches(id: String) = accepting && isJobActive() && id == utterance && !done

    fun begin(id: String, sampleRate: Int, encoding: Int, channels: Int) {
        val action = synchronized(this) {
            if (!matches(id)) return
            try {
                check(!began) { "Duplicate synthesis format" }
                val next = PcmFormat(sampleRate, channels, encoding)
                require(pcmFormat == null || pcmFormat == next) { "PCM format changed between chunks" }
                pcmFormat = next
                began = true
                null
            } catch (error: Throwable) { failLocked(error) }
        }
        action?.let(::notifyFailure)
    }

    fun audio(id: String, bytes: ByteArray) {
        val action = synchronized(callbackLock) {
            var rejected: FailureAction? = null
            val message = synchronized(this) {
                if (!matches(id)) return
                try {
                    check(began) { "PCM arrived before format" }
                    require(bytes.size <= 1024 * 1024) { "Oversized PCM callback" }
                    require(bytes.size.toLong() <= WavWriter.MAX_PCM_BYTES - acceptedBytes) { "RIFF size limit exceeded" }
                    require(availableBytes() - bytes.size > QuotaProjector.FILESYSTEM_RESERVE) { "Insufficient staging space" }
                    if (bytes.isEmpty()) return
                    val data = Message.Data(bytes.copyOf())
                    chunkBytes += bytes.size
                    acceptedBytes += bytes.size
                    data
                } catch (error: Throwable) { rejected = failLocked(error); null }
            }
            rejected ?: message?.let(::enqueue)
        }
        action?.let(::notifyFailure)
    }

    fun done(id: String) {
        val action = synchronized(callbackLock) {
            var rejected: FailureAction? = null
            val message = synchronized(this) {
                if (!matches(id)) return
                try {
                    check(began && chunkBytes > 0 && chunkBytes % format.blockAlign == 0L) { "Missing or incomplete PCM frames" }
                    done = true
                    Message.Barrier(requireNotNull(pending))
                } catch (error: Throwable) { rejected = failLocked(error); null }
            }
            rejected ?: message?.let(::enqueue)
        }
        action?.let(::notifyFailure)
    }

    private fun enqueue(message: Message): FailureAction? = try {
        // Wait outside the state monitor, so IO and cancellation can make progress.
        queue.trySendBlocking(message).getOrThrow()
        null
    } catch (error: Throwable) { synchronized(this) { failLocked(error) } }

    fun error(id: String, error: Throwable) {
        val action = synchronized(this) { if (matches(id)) failLocked(error) else null }
        action?.let(::notifyFailure)
    }

    // Called only under the state monitor. Notifications happen after all locks are released.
    private fun failLocked(error: Throwable): FailureAction {
        if (failure == null) failure = error
        accepting = false
        return FailureAction(pending, requireNotNull(failure))
    }

    private fun notifyFailure(action: FailureAction) {
        // Free callbacks before resuming a Main.immediate waiter which may stop TTS
        // synchronously and wait for those callbacks to return.
        queue.cancel(CancellationException("PCM writer aborted", action.error))
        action.result?.completeExceptionally(action.error)
    }

    // Invalidation rejects callbacks synchronously, before tts.stop(), without
    // holding the state monitor across a resumed cleanup continuation.
    fun invalidate(error: Throwable = CancellationException("Export cancelled")) {
        val action = synchronized(this) { failLocked(error) }
        notifyFailure(action)
    }

    suspend fun closeAndJoin() {
        queue.close()
        writer.join()
        synchronized(this) { failure?.let { throw it } }
    }
}
