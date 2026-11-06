package com.pdfchemy.app.billing

import com.pdfchemy.app.logic.LatestRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class EntitlementStorageState(
    val failed: Boolean = false, val saving: Boolean = false, val readFailed: Boolean = false
)

/** Serializes the encrypted cache independently of Play ownership/acknowledgement. */
internal class EntitlementPersistenceCoordinator(
    private val store: EntitlementStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher
) {
    // Multiple activity owners must not let an old retry overwrite a newer decision.
    companion object {
        private val writes = Mutex()
        private val requests = LatestRequest()
        private val current = MutableStateFlow(EntitlementStorageState())
        private var desired: Boolean? = null

        internal fun resetForTesting() {
            requests.invalidate()
            desired = null
            current.value = EntitlementStorageState()
        }
    }

    val state = current.asStateFlow()
    private var job: Job? = null

    fun readFailed() { current.value = EntitlementStorageState(failed = true, readFailed = true) }
    fun hasPendingWrite(): Boolean = desired != null

    fun retry() {
        val value = desired ?: return
        if (!current.value.saving) save(value)
    }

    fun save(value: Boolean) {
        desired = value
        val ticket = requests.begin()
        job?.cancel()
        job = scope.launch {
            try {
                repeat(3) { attempt ->
                    if (!requests.isCurrent(ticket)) return@launch
                    current.value = current.value.copy(saving = true, readFailed = false)
                    val saved = writes.withLock {
                        if (!requests.isCurrent(ticket)) return@launch
                        // A blocking commit cannot be interrupted safely. Keep its mutex until
                        // it finishes so a cancelled old write cannot land after a newer one.
                        withContext(NonCancellable + dispatcher) {
                            try { store.write(value) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { false }
                        }
                    }
                    if (!requests.isCurrent(ticket)) return@launch
                    if (saved) {
                        desired = null
                        current.value = EntitlementStorageState()
                        return@launch
                    }
                    current.value = EntitlementStorageState(failed = true)
                    if (attempt < 2) delay(1_000L shl attempt)
                }
            } finally {
                if (requests.isCurrent(ticket) && desired != null)
                    current.value = EntitlementStorageState(failed = true)
            }
        }
    }
}
