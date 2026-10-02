package com.pdfchemy.app.security

import android.os.IBinder
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Tokenized worker gate enforcing single-flight admission, atomic ownership,
 * distinct execution vs Host-ACK watchdogs, and stale-token isolation.
 */
class WorkerGate(private val terminate: () -> Unit) : Closeable {

    enum class State {
        IDLE,
        RESERVED,
        RUNNING,
        AWAITING_HOST_ACCEPT,
        FAILED_POISONED
    }

    private val lock = Any()
    private val nextToken = AtomicLong(1L)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var currentToken: Long = 0L
    private var currentState: State = State.IDLE
    private var activeWatchdog: ScheduledFuture<*>? = null
    private var currentOwner: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    /**
     * Begins an operation, returning an exclusive token (> 0) or 0 if BUSY.
     */
    fun begin(ownerBinder: IBinder?, deadlineMillis: Long = SecurityLimits.WORKER_DEADLINE_MS): Long = synchronized(lock) {
        if (currentState != State.IDLE || currentToken != 0L) {
            return 0L
        }
        val token = nextToken.getAndIncrement()
        currentToken = token
        currentState = State.RESERVED

        armWatchdogLocked(deadlineMillis)

        if (ownerBinder != null) {
            val recipient = IBinder.DeathRecipient {
                synchronized(lock) {
                    if (currentToken == token && currentState != State.IDLE) {
                        currentState = State.FAILED_POISONED
                        terminate()
                    }
                }
            }
            runCatching {
                ownerBinder.linkToDeath(recipient, 0)
                currentOwner = ownerBinder
                deathRecipient = recipient
            }.onFailure {
                // If linking fails because owner is already dead, terminate
                currentState = State.FAILED_POISONED
                terminate()
            }
        }
        return token
    }

    /**
     * Transitions from RESERVED to RUNNING for the matching token.
     */
    fun startExecution(operationId: Long): Boolean = synchronized(lock) {
        if (operationId != currentToken || currentState != State.RESERVED) {
            return false
        }
        currentState = State.RUNNING
        return true
    }

    /**
     * Transitions from RUNNING to AWAITING_HOST_ACCEPT for the matching token,
     * re-arming the watchdog with the Host acceptance deadline.
     */
    fun markAwaitingHostAccept(
        operationId: Long,
        hostDeadlineMillis: Long = SecurityLimits.HOST_ACCEPT_DEADLINE_MS
    ): Boolean = synchronized(lock) {
        if (operationId != currentToken || currentState != State.RUNNING) {
            return false
        }
        currentState = State.AWAITING_HOST_ACCEPT
        armWatchdogLocked(hostDeadlineMillis)
        return true
    }

    /**
     * Completes and releases the gate if the exact token matches and is not poisoned.
     */
    fun complete(operationId: Long): Boolean = synchronized(lock) {
        if (operationId != currentToken) {
            return false
        }
        if (currentState == State.FAILED_POISONED) {
            return false
        }
        disarmWatchdogLocked()
        unlinkOwnerLocked()
        currentToken = 0L
        currentState = State.IDLE
        return true
    }

    /**
     * Tests ownership and initiates termination while keeping the gate poisoned.
     */
    fun terminateIfOwner(operationId: Long): Boolean = synchronized(lock) {
        if (operationId != currentToken || currentToken == 0L) {
            return false
        }
        currentState = State.FAILED_POISONED
        disarmWatchdogLocked()
        unlinkOwnerLocked()
        terminate()
        return true
    }

    /**
     * Returns true if the given token is the currently admitted owner.
     */
    fun isOwner(operationId: Long): Boolean = synchronized(lock) {
        return operationId != 0L && operationId == currentToken
    }

    /**
     * Returns true if any operation currently owns or is executing on the gate.
     */
    fun isBusy(): Boolean = synchronized(lock) {
        return currentState != State.IDLE || currentToken != 0L
    }

    /**
     * Marks the current operation as poisoned (e.g. on execution failure).
     */
    fun poison(operationId: Long): Boolean = synchronized(lock) {
        if (operationId != currentToken || currentToken == 0L) {
            return false
        }
        currentState = State.FAILED_POISONED
        return true
    }

    /**
     * Compatibility helper for simple single-process workers (e.g. NativeRenderer).
     */
    fun acquire(deadlineMillis: Long): Closeable? {
        val token = begin(null, deadlineMillis)
        if (token == 0L) return null
        if (!startExecution(token)) return null
        return Closeable { complete(token) }
    }

    private fun armWatchdogLocked(millis: Long) {
        activeWatchdog?.cancel(false)
        activeWatchdog = scheduler.schedule({
            synchronized(lock) {
                if (currentToken != 0L && currentState != State.IDLE) {
                    currentState = State.FAILED_POISONED
                    terminate()
                }
            }
        }, millis, TimeUnit.MILLISECONDS)
    }

    private fun disarmWatchdogLocked() {
        activeWatchdog?.cancel(false)
        activeWatchdog = null
    }

    private fun unlinkOwnerLocked() {
        val owner = currentOwner
        val recipient = deathRecipient
        if (owner != null && recipient != null) {
            runCatching { owner.unlinkToDeath(recipient, 0) }
        }
        currentOwner = null
        deathRecipient = null
    }

    override fun close() {
        synchronized(lock) {
            disarmWatchdogLocked()
            unlinkOwnerLocked()
            scheduler.shutdownNow()
        }
    }
}
