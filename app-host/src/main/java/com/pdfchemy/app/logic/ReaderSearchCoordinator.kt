package com.pdfchemy.app.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * State and lifecycle coordinator for in-document Reader search (RA-02 / FOLLOW-01).
 *
 * Guarantees:
 * 1. Immediate invalidation on Clear: Tapping Clear invalidates [LatestRequest] and cancels
 *    any in-flight search coroutine so late-arriving results are strictly rejected.
 * 2. Zero delayed scrolling: When a search is cancelled, invalidated, or cleared, scroll
 *    callbacks are never invoked and match state remains empty.
 * 3. Monotonic ordering: Newer search submissions cancel and supersede older in-flight requests.
 * 4. Blank query rejection: Blank or whitespace-only queries reject execution and clear state.
 * 5. Lifecycle safety: Dismissing search or disposing the screen cancels active background work.
 */
class ReaderSearchCoordinator(
    val generation: LatestRequest = LatestRequest(),
    var onStateChanged: (() -> Unit)? = null
) {
    var query: String = ""
        private set

    var matches: List<TextMatchOccurrence> = emptyList()
        private set

    var currentMatchIndex: Int = 0
        private set

    var isSearching: Boolean = false
        private set

    var activeJob: Job? = null
        private set

    val matchCount: Int get() = matches.size

    val currentMatch: TextMatchOccurrence?
        get() = if (matches.isNotEmpty() && currentMatchIndex in matches.indices) {
            matches[currentMatchIndex]
        } else null

    fun onQueryChanged(newQuery: String) {
        query = newQuery
        cancelAndInvalidate()
        matches = emptyList()
        currentMatchIndex = 0
        isSearching = false
        onStateChanged?.invoke()
    }

    fun clear() {
        cancelAndInvalidate()
        query = ""
        matches = emptyList()
        currentMatchIndex = 0
        isSearching = false
        onStateChanged?.invoke()
    }

    fun submitSearch(
        scope: CoroutineScope,
        searchQuery: String = query,
        searcher: suspend () -> List<TextMatchOccurrence>,
        onScrollToMatch: suspend (pageIndex: Int) -> Unit,
        onError: ((Throwable) -> Unit)? = null
    ): Job? {
        query = searchQuery
        if (searchQuery.isBlank()) {
            clear()
            return null
        }

        cancelAndInvalidate()
        val ticket = generation.begin()
        isSearching = true
        onStateChanged?.invoke()

        val job = scope.launch {
            try {
                val results = searcher()
                if (!generation.isCurrent(ticket) || query.isBlank()) {
                    return@launch
                }
                matches = results
                currentMatchIndex = 0
                onStateChanged?.invoke()
                if (results.isNotEmpty()) {
                    onScrollToMatch(results[0].pageIndex)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (generation.isCurrent(ticket)) {
                    onError?.invoke(t)
                }
            } finally {
                if (generation.isCurrent(ticket)) {
                    isSearching = false
                    onStateChanged?.invoke()
                }
            }
        }
        activeJob = job
        return job
    }

    fun nextMatch(onScrollToMatch: (pageIndex: Int) -> Unit): Boolean {
        if (matches.isEmpty()) return false
        val newIdx = if (currentMatchIndex < matches.size - 1) currentMatchIndex + 1 else 0
        currentMatchIndex = newIdx
        onStateChanged?.invoke()
        onScrollToMatch(matches[newIdx].pageIndex)
        return true
    }

    fun previousMatch(onScrollToMatch: (pageIndex: Int) -> Unit): Boolean {
        if (matches.isEmpty()) return false
        val newIdx = if (currentMatchIndex > 0) currentMatchIndex - 1 else matches.size - 1
        currentMatchIndex = newIdx
        onStateChanged?.invoke()
        onScrollToMatch(matches[newIdx].pageIndex)
        return true
    }

    fun cancelAndInvalidate() {
        activeJob?.cancel()
        activeJob = null
        generation.invalidate()
    }

    fun dispose() {
        clear()
    }
}
