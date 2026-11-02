package com.pdfchemy.app.security

import android.graphics.RectF
import com.pdfchemy.app.logic.ReaderSearchCoordinator
import com.pdfchemy.app.logic.TextMatchOccurrence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderSearchSecurityTest {

    private fun sampleMatch(page: Int, text: String = "needle"): TextMatchOccurrence {
        return TextMatchOccurrence(
            pageIndex = page,
            matchedText = text,
            snippet = "... $text ...",
            bounds = RectF(10f, 20f, 100f, 40f),
            fontSize = 12f
        )
    }

    @Test
    fun testClearInvalidatesGenerationAndRejectsDelayedResults() = runTest {
        val coordinator = ReaderSearchCoordinator()
        val deferred = CompletableDeferred<List<TextMatchOccurrence>>()
        var scrolledPage: Int? = null

        val job = coordinator.submitSearch(
            scope = this,
            searchQuery = "confidential",
            searcher = { deferred.await() },
            onScrollToMatch = { scrolledPage = it }
        )

        assertNotNull(job)
        assertTrue(coordinator.isSearching)
        assertEquals("confidential", coordinator.query)
        assertNull(scrolledPage)

        // User taps Clear while search is running
        coordinator.clear()

        assertEquals("", coordinator.query)
        assertTrue(coordinator.matches.isEmpty())
        assertFalse(coordinator.isSearching)
        assertEquals(0, coordinator.currentMatchIndex)

        // Late-arriving search response completes
        deferred.complete(listOf(sampleMatch(5), sampleMatch(10)))
        advanceUntilIdle()

        // Results MUST be dropped; scroll callback MUST NOT be invoked
        assertNull("Delayed search result must not trigger viewport scroll after Clear", scrolledPage)
        assertTrue("Matches list must remain empty after Clear", coordinator.matches.isEmpty())
        assertFalse(coordinator.isSearching)
    }

    @Test
    fun testClearCancelsActiveInFlightJob() = runTest {
        val coordinator = ReaderSearchCoordinator()
        val deferred = CompletableDeferred<List<TextMatchOccurrence>>()

        coordinator.submitSearch(
            scope = this,
            searchQuery = "target",
            searcher = { deferred.await() },
            onScrollToMatch = {}
        )

        val activeJob = coordinator.activeJob
        assertNotNull(activeJob)
        assertTrue(activeJob!!.isActive)

        coordinator.clear()

        assertTrue("Active search job must be cancelled on Clear", activeJob.isCancelled)
        assertNull("Active job reference must be cleared", coordinator.activeJob)
    }

    @Test
    fun testQueryChangedCancelsInFlightSearchAndRejectsLateCompletion() = runTest {
        val coordinator = ReaderSearchCoordinator()
        val deferredFirst = CompletableDeferred<List<TextMatchOccurrence>>()
        var scrolledPage: Int? = null

        coordinator.submitSearch(
            scope = this,
            searchQuery = "first",
            searcher = { deferredFirst.await() },
            onScrollToMatch = { scrolledPage = it }
        )

        // User edits the query text before search finishes
        coordinator.onQueryChanged("second")

        assertEquals("second", coordinator.query)
        assertTrue(coordinator.matches.isEmpty())
        assertFalse(coordinator.isSearching)

        // First search finishes late
        deferredFirst.complete(listOf(sampleMatch(3)))
        advanceUntilIdle()

        assertNull("Late results from previous query must not scroll", scrolledPage)
        assertTrue("Matches must remain empty", coordinator.matches.isEmpty())
    }

    @Test
    fun testSubsequentSearchSupersedesInFlightSearch() = runTest {
        val coordinator = ReaderSearchCoordinator()
        val deferredFirst = CompletableDeferred<List<TextMatchOccurrence>>()
        val deferredSecond = CompletableDeferred<List<TextMatchOccurrence>>()
        var scrolledPage: Int? = null

        coordinator.submitSearch(
            scope = this,
            searchQuery = "first",
            searcher = { deferredFirst.await() },
            onScrollToMatch = { scrolledPage = it }
        )

        coordinator.submitSearch(
            scope = this,
            searchQuery = "second",
            searcher = { deferredSecond.await() },
            onScrollToMatch = { scrolledPage = it }
        )

        // First completes late
        deferredFirst.complete(listOf(sampleMatch(8)))
        advanceUntilIdle()

        assertNull("Superseded first search must not scroll", scrolledPage)

        // Second completes
        deferredSecond.complete(listOf(sampleMatch(15)))
        advanceUntilIdle()

        assertEquals("Current search must scroll to its match", 15, scrolledPage)
        assertEquals(1, coordinator.matchCount)
        assertEquals(15, coordinator.matches[0].pageIndex)
    }

    @Test
    fun testBlankQueryRejectedWithoutInvokingSearcherOrScrolling() = runTest {
        val coordinator = ReaderSearchCoordinator()
        var searcherCalled = false
        var scrolled = false

        val job = coordinator.submitSearch(
            scope = this,
            searchQuery = "   ",
            searcher = {
                searcherCalled = true
                listOf(sampleMatch(1))
            },
            onScrollToMatch = { scrolled = true }
        )

        assertNull("Blank query must return null Job", job)
        assertFalse("Blank query must not invoke searcher", searcherCalled)
        assertFalse("Blank query must not scroll", scrolled)
        assertFalse(coordinator.isSearching)
        assertEquals("", coordinator.query)
        assertTrue(coordinator.matches.isEmpty())
    }

    @Test
    fun testDelayedErrorSuppressedWhenSearchCleared() = runTest {
        val coordinator = ReaderSearchCoordinator()
        val deferred = CompletableDeferred<List<TextMatchOccurrence>>()
        var errorReported: Throwable? = null

        coordinator.submitSearch(
            scope = this,
            searchQuery = "fail",
            searcher = { deferred.await() },
            onScrollToMatch = {},
            onError = { errorReported = it }
        )

        coordinator.clear()

        // Deferred completes exceptionally after Clear
        deferred.completeExceptionally(IOException("Storage read error"))
        advanceUntilIdle()

        assertNull("Error from cleared search must be suppressed", errorReported)
        assertFalse(coordinator.isSearching)
    }

    @Test
    fun testDelayedErrorReportedWhenSearchStillCurrent() = runTest {
        val coordinator = ReaderSearchCoordinator()
        val deferred = CompletableDeferred<List<TextMatchOccurrence>>()
        var errorReported: Throwable? = null

        coordinator.submitSearch(
            scope = this,
            searchQuery = "fail",
            searcher = { deferred.await() },
            onScrollToMatch = {},
            onError = { errorReported = it }
        )

        deferred.completeExceptionally(IOException("Storage read error"))
        advanceUntilIdle()

        assertNotNull("Error from active search must be reported", errorReported)
        assertTrue(errorReported is IOException)
        assertFalse(coordinator.isSearching)
    }

    @Test
    fun testMatchNavigationWrapping() = runTest {
        val coordinator = ReaderSearchCoordinator()
        val matches = listOf(sampleMatch(2), sampleMatch(7), sampleMatch(15))
        var scrolledPage: Int? = null

        coordinator.submitSearch(
            scope = this,
            searchQuery = "test",
            searcher = { matches },
            onScrollToMatch = { scrolledPage = it }
        )
        advanceUntilIdle()

        assertEquals(2, scrolledPage)
        assertEquals(0, coordinator.currentMatchIndex)

        // Next -> index 1 (page 7)
        assertTrue(coordinator.nextMatch { scrolledPage = it })
        assertEquals(7, scrolledPage)
        assertEquals(1, coordinator.currentMatchIndex)

        // Next -> index 2 (page 15)
        assertTrue(coordinator.nextMatch { scrolledPage = it })
        assertEquals(15, scrolledPage)
        assertEquals(2, coordinator.currentMatchIndex)

        // Next -> wraps to index 0 (page 2)
        assertTrue(coordinator.nextMatch { scrolledPage = it })
        assertEquals(2, scrolledPage)
        assertEquals(0, coordinator.currentMatchIndex)

        // Previous -> wraps backward to index 2 (page 15)
        assertTrue(coordinator.previousMatch { scrolledPage = it })
        assertEquals(15, scrolledPage)
        assertEquals(2, coordinator.currentMatchIndex)

        // Previous -> index 1 (page 7)
        assertTrue(coordinator.previousMatch { scrolledPage = it })
        assertEquals(7, scrolledPage)
        assertEquals(1, coordinator.currentMatchIndex)
    }

    @Test
    fun testDisposeCancelsJobAndInvalidatesGeneration() = runTest {
        val coordinator = ReaderSearchCoordinator()
        val deferred = CompletableDeferred<List<TextMatchOccurrence>>()

        coordinator.submitSearch(
            scope = this,
            searchQuery = "disposing",
            searcher = { deferred.await() },
            onScrollToMatch = {}
        )

        val job = coordinator.activeJob
        assertNotNull(job)
        assertTrue(job!!.isActive)

        coordinator.dispose()

        assertTrue(job.isCancelled)
        assertNull(coordinator.activeJob)
        assertEquals("", coordinator.query)
        assertTrue(coordinator.matches.isEmpty())
        assertFalse(coordinator.isSearching)
    }

    @Test
    fun testStateChangedCallbackInvokedOnAllMutations() = runTest {
        var callbackCount = 0
        val coordinator = ReaderSearchCoordinator(onStateChanged = { callbackCount++ })

        coordinator.onQueryChanged("abc")
        assertEquals(1, callbackCount)

        coordinator.submitSearch(
            scope = this,
            searchQuery = "abc",
            searcher = { listOf(sampleMatch(1), sampleMatch(2)) },
            onScrollToMatch = {}
        )
        advanceUntilIdle()

        // submitSearch triggers callback on start, on completion, on finish
        assertTrue(callbackCount >= 3)

        val beforeNav = callbackCount
        coordinator.nextMatch {}
        assertEquals(beforeNav + 1, callbackCount)

        val beforeClear = callbackCount
        coordinator.clear()
        assertEquals(beforeClear + 1, callbackCount)
    }
}
