package com.pdfchemy.app.security

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** No pending queue. Deadline runs on a dedicated thread, outside parser execution. */
class WorkerGate(private val terminate: () -> Unit) : Closeable {
    private val active = AtomicBoolean(false)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    fun acquire(deadlineMillis: Long): Closeable? {
        if (!active.compareAndSet(false, true)) return null
        val finished = AtomicBoolean(false)
        val alarm = watchdog.schedule({ if (finished.compareAndSet(false, true)) terminate() }, deadlineMillis, TimeUnit.MILLISECONDS)
        return Closeable {
            if (finished.compareAndSet(false, true)) {
                alarm.cancel(false)
                active.set(false)
            }
        }
    }
    override fun close() { watchdog.shutdownNow() }
}
