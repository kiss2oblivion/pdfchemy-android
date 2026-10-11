package com.pdfchemy.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * Handle representing an active secure lease on an Android [Window].
 */
interface SecureLease {
    val window: Window
    val tag: String
    val isReleased: Boolean
    fun release()
}

/**
 * Coordinates [WindowManager.LayoutParams.FLAG_SECURE] ownership per [Window].
 *
 * Prevents screen transition races where an outgoing sensitive screen clearing
 * the flag leaves an incoming sensitive screen unshielded. Also preserves
 * flags independently established before this manager acquired any leases.
 */
object WindowSecureManager {
    private val lock = Any()
    private val states = WeakHashMap<Window, WindowSecureState>()

    private class WindowSecureState(
        val window: Window,
        val initialExternalSecure: Boolean
    ) {
        private val activeTokens = mutableMapOf<Any, String>()

        fun acquire(token: Any, tag: String) {
            val wasEmpty = activeTokens.isEmpty()
            activeTokens[token] = tag
            if (wasEmpty) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        fun release(token: Any) {
            val removed = activeTokens.remove(token)
            if (removed != null && activeTokens.isEmpty()) {
                if (!initialExternalSecure) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        val activeCount: Int
            get() = activeTokens.size

        val isSecure: Boolean
            get() = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }

    private class LeaseImpl(
        override val window: Window,
        override val tag: String,
        private val token: Any
    ) : SecureLease {
        @Volatile
        private var _isReleased = false

        override val isReleased: Boolean
            get() = _isReleased

        override fun release() {
            synchronized(lock) {
                if (!_isReleased) {
                    _isReleased = true
                    states[window]?.release(token)
                }
            }
        }
    }

    /**
     * Acquires a secure lease for [window].
     * Sets [WindowManager.LayoutParams.FLAG_SECURE] on the window if not already active.
     */
    fun acquire(window: Window, tag: String = "SecureLease"): SecureLease = synchronized(lock) {
        val state = states.getOrPut(window) {
            val hadFlag = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            WindowSecureState(window, hadFlag)
        }
        val token = Any()
        state.acquire(token, tag)
        LeaseImpl(window, tag, token)
    }

    /**
     * Returns the number of currently active leases for [window].
     */
    fun activeLeaseCount(window: Window): Int = synchronized(lock) {
        states[window]?.activeCount ?: 0
    }

    /**
     * Checks if [WindowManager.LayoutParams.FLAG_SECURE] is currently set on [window].
     */
    fun isSecure(window: Window): Boolean {
        return (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }

    /**
     * Checks if this window was independently secure before any lease was acquired.
     */
    fun wasInitiallySecure(window: Window): Boolean = synchronized(lock) {
        states[window]?.initialExternalSecure ?: false
    }

    /**
     * Clears all recorded state for [window] (or all windows if null). For test isolation.
     */
    internal fun resetForTesting(window: Window? = null) = synchronized(lock) {
        if (window != null) {
            states.remove(window)
        } else {
            states.clear()
        }
    }
}

/**
 * Traverses context hierarchy to locate hosting [Activity].
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Ensures [WindowManager.LayoutParams.FLAG_SECURE] is set on the hosting window
 * while this composable is in the composition hierarchy.
 *
 * Multiple sensitive screens coordinate their leases via [WindowSecureManager],
 * guaranteeing that screen transitions never prematurely strip window protection.
 */
@Composable
fun SecureScreenContent(tag: String = "SecureScreenContent") {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        val window = activity?.window
        val lease = window?.let { WindowSecureManager.acquire(it, tag) }
        onDispose {
            lease?.release()
        }
    }
}
