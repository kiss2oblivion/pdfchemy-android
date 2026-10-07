package com.pdfchemy.app.logic.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** One bounded writer per job. Callback identity and queue insertion share the same lock. */
class PcmWriter(
    private val jobId: UUID,
    private val generation: UUID,
    private val raw: File,
    scope: CoroutineScope,
    private val availableBytes: () -> Long = { raw.parentFile!!.usableSpace },
    private val openOutput: () -> OutputStream = { raw.outputStream() },
    private val isJobActive: () -> Boolean = { true }
) {
    private sealed class Message {
        class Data(val bytes: ByteArray) : Message()
        class Barrier(val result: CompletableDeferred<Long>) : Message()
    }
    private val queue = Channel<Message>(64)
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
                    if (!synchronized(this@PcmWriter) { accepting && isJobActive() }) break
                    when (message) {
                        is Message.Data -> output.write(message.bytes)
                        is Message.Barrier -> {
                            output.flush()
                            synchronized(this@PcmWriter) {
                                if (accepting) message.result.complete(chunkBytes)
                                else message.result.completeExceptionally(CancellationException("Export aborted"))
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(this@PcmWriter) { fail(error) }
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

    @Synchronized fun begin(id: String, sampleRate: Int, encoding: Int, channels: Int) {
        if (!matches(id)) return
        try {
            check(!began) { "Duplicate synthesis format" }
            val next = PcmFormat(sampleRate, channels, encoding)
            require(pcmFormat == null || pcmFormat == next) { "PCM format changed between chunks" }
            pcmFormat = next; began = true
        } catch (error: Throwable) { fail(error) }
    }

    @Synchronized fun audio(id: String, bytes: ByteArray) {
        if (!matches(id)) return
        try {
            check(began) { "PCM arrived before format" }
            require(bytes.size <= 1024 * 1024) { "Oversized PCM callback" }
            require(bytes.size.toLong() <= WavWriter.MAX_PCM_BYTES - acceptedBytes) { "RIFF size limit exceeded" }
            require(availableBytes() - bytes.size > QuotaProjector.FILESYSTEM_RESERVE) { "Insufficient staging space" }
            if (bytes.isNotEmpty()) {
                check(queue.trySend(Message.Data(bytes.copyOf())).isSuccess) { "PCM writer queue overflow" }
                chunkBytes += bytes.size; acceptedBytes += bytes.size
            }
        } catch (error: Throwable) { fail(error) }
    }

    @Synchronized fun done(id: String) {
        if (!matches(id)) return
        try {
            check(began && chunkBytes > 0 && chunkBytes % format.blockAlign == 0L) { "Missing or incomplete PCM frames" }
            done = true
            check(queue.trySend(Message.Barrier(requireNotNull(pending))).isSuccess) { "PCM writer queue overflow" }
        } catch (error: Throwable) { fail(error) }
    }

    @Synchronized fun error(id: String, error: Throwable) { if (matches(id)) fail(error) }

    private fun fail(error: Throwable) {
        if (failure == null) failure = error
        accepting = false
        pending?.completeExceptionally(error)
        queue.cancel(CancellationException("PCM writer aborted", error))
    }

    // Invalidation is synchronous, before tts.stop(), even if the caller must await IO cleanup later.
    @Synchronized fun invalidate(error: Throwable = CancellationException("Export cancelled")) { fail(error) }

    suspend fun closeAndJoin() {
        queue.close()
        writer.join()
        synchronized(this) { failure?.let { throw it } }
    }
}
