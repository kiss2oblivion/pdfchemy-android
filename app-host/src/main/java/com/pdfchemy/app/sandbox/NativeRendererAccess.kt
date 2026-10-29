package com.pdfchemy.app.sandbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates host clients before private output allocation and isolated renderer admission. */
internal object NativeRendererAccess {
    private val mutex = Mutex()

    class Permit internal constructor(private val owner: Any) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) mutex.unlock(owner)
        }
    }

    suspend fun acquire(): Permit {
        currentCoroutineContext().ensureActive()
        val owner = Any()
        mutex.lock(owner)
        return Permit(owner)
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        val permit = acquire()
        try { return block() }
        finally { permit.close() }
    }
}
