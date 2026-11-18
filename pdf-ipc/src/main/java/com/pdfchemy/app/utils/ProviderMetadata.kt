package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.CompletableFuture
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Provider IPC never runs or waits on Main. Uncooperative providers occupy at most two workers. */
object ProviderMetadata {
    data class Value(val name: String? = null, val size: Long = 0, val mime: String? = null)
    private data class Cached(val value: Value, val at: Long, val owner: Request)
    private class Request(val epoch: Long) {
        val signal = CancellationSignal()
        val result = CompletableFuture<Value>()
        @Volatile var abandoned = false
    }
    private val lock = Any()
    private var epoch = 0L
    private val cache = object : LinkedHashMap<String, Cached>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 128
    }
    private val active = mutableMapOf<String, Request>()
    private fun pool(name: String) = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(), { task -> Thread(task, name).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val workers = pool("ProviderMetadata")
    // CancellationSignal.cancel itself can make remote IPC. It must not block the caller either.
    private val cancellations = pool("ProviderMetadataCancel")
    private const val DEADLINE_MS = 750L

    fun cached(uri: Uri): Value? = synchronized(lock) {
        cache[uri.toString()]?.takeIf { SystemClock.elapsedRealtime() - it.at < 60_000 }?.value
    }

    fun clear() {
        val requests = synchronized(lock) { epoch++; cache.clear(); active.values.toList() }
        requests.forEach(::abandon)
    }

    private fun abandon(request: Request) {
        synchronized(lock) {
            request.abandoned = true
            // A completion racing the deadline must not leave a stale cached result.
            cache.entries.removeAll { it.value.owner === request }
        }
        request.result.complete(Value())
        try { cancellations.execute { request.signal.cancel() } } catch (_: RejectedExecutionException) { }
    }

    /** Legacy background callers receive the same bounded behavior as coroutine callers. */
    fun read(context: Context, uri: Uri): Value {
        cached(uri)?.let { return it }
        if (uri.scheme != "content" || Looper.myLooper() == Looper.getMainLooper()) return Value()
        val resolver = try { (runCatching { context.applicationContext }.getOrNull() ?: context).contentResolver }
            catch (_: Exception) { return Value() }
        val key = uri.toString()
        val request = synchronized(lock) {
            active[key] ?: Request(epoch).also { request ->
                active[key] = request
                try {
                    workers.execute {
                        try {
                            var name: String? = null
                            var size = 0L
                            try { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                                null, null, null, request.signal)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    if (n >= 0) name = cursor.getString(n)?.take(1024)
                                    if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s).coerceAtLeast(0)
                                }
                            }
                            } catch (_: Exception) { /* Some stream providers support MIME but no openable-column query. */ }
                            val mime = if (request.abandoned) null else runCatching { resolver.getType(uri) }.getOrNull()?.take(255)
                            val value = Value(name, size, mime)
                            synchronized(lock) {
                                if (!request.abandoned && request.epoch == epoch) cache[key] = Cached(value, SystemClock.elapsedRealtime(), request)
                            }
                            request.result.complete(value)
                        } catch (_: Exception) { request.result.complete(Value()) }
                        finally { synchronized(lock) { if (active[key] === request) active.remove(key) } }
                    }
                } catch (_: RejectedExecutionException) {
                    active.remove(key)
                    request.result.complete(Value())
                }
            }
        }
        return try { request.result.get(DEADLINE_MS, TimeUnit.MILLISECONDS) }
        catch (interrupted: InterruptedException) { abandon(request); throw interrupted }
        catch (_: Exception) { abandon(request); Value() }
    }

    suspend fun load(context: Context, uri: Uri): Value = runInterruptible(Dispatchers.IO) { read(context, uri) }
}
