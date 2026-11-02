# FOLLOW-01 execution receipt: Reader search Clear generation invalidation, delayed result rejection, and scroll prevention (RA-02)

Date: 2026-10-23. Commit: `72883d32`. Release: **HOLD**. Retained RA-02 / FOLLOW-01 reader search generation invalidation on Clear, active job cancellation, delayed search result rejection, scroll callback prevention, deterministic test suite, and Android build gates are complete.

## Problem and change

Previously in `PdfReaderScreen.kt`:
1. **Uninvalidated Ticket on Clear:** When the user clicked the Clear button (`Icons.Rounded.Close`), the click listener executed:
   ```kotlin
   IconButton(onClick = { searchQuery = ""; searchMatches = emptyList() }) {
       Icon(Icons.Rounded.Close, contentDescription = "Clear")
   }
   ```
   It did NOT call `searchGeneration.invalidate()`, did NOT cancel any in-flight search coroutine `Job`, and did NOT reset `isSearching = false`.
2. **Late-Arriving Result Leak & Viewport Scroll:** When an asynchronous search (`PdfFindAndReplaceEngine.findOccurrences`) completed on a worker thread after the user tapped Clear:
   - `searchGeneration.isCurrent(ticket)` evaluated to `true` because the ticket generation had never been advanced.
   - `searchMatches = summary.occurrences` repopulated the match list despite the search field being cleared.
   - `listState.animateScrollToItem(searchMatches[0].pageIndex)` unexpectedly animated the reader scroll position to the first match in the cleared query.
3. **Unprotected Query Edit & Search Dismissal:** Modifying text did not cancel in-flight jobs, toggling `isSearchMode = false` left background searches running, and delayed errors could trigger toasts for cleared searches.

### Resolution

1. **`ReaderSearchCoordinator` State & Lifecycle Engine (`ReaderSearchCoordinator.kt`):**
   - Encapsulated in-document search state, request ticket generations (`LatestRequest`), active coroutine jobs, and UI callbacks into a dedicated coordinator in `com.pdfchemy.app.logic`.
   - **Immediate Invalidation on Clear (`clear()`):**
     - Cancels active in-flight coroutine `Job` immediately.
     - Advances `LatestRequest` via `generation.invalidate()`.
     - Resets `query = ""`, `matches = emptyList()`, `currentMatchIndex = 0`, `isSearching = false`.
     - Invokes `onStateChanged` to update the UI instantly.
   - **Strict Delayed Completion Check:**
     - On completion of `searcher()`, enforces:
       ```kotlin
       if (!generation.isCurrent(ticket) || query.isBlank()) {
           return@launch
       }
       ```
     - If the user tapped Clear, changed the query, or closed search while the searcher was running, late results are dropped unconditionally.
     - The scroll callback `onScrollToMatch` is never invoked for superseded or cleared searches.
   - **Query Change Cancellation (`onQueryChanged(newQuery)`):** Cancels active jobs and advances generation immediately upon text edit.
   - **Blank Query Rejection:** Whitespace-only or empty submissions reject execution, clear state, and return `null` without launching background workers or scrolling.
   - **Error Isolation:** Exceptions occurring in stale/cleared requests are suppressed and never surfaced to `viewModel.notifyError`.
   - **Circular Match Navigation:** `nextMatch` and `previousMatch` wrap boundaries cleanly (0 -> last, last -> 0).

2. **`PdfReaderScreen.kt` Integration:**
   - Introduced `val searchCoordinator = remember { ReaderSearchCoordinator() }`.
   - Synchronized coordinator state with Compose state via `DisposableEffect(searchCoordinator)` and cleaned up on screen exit.
   - Wired `searchCoordinator.clear()` to the Clear `IconButton`.
   - Wired `searchCoordinator.onQueryChanged(it)` to `OutlinedTextField.onValueChange`.
   - Wired `searchCoordinator.submitSearch(...)` to `keyboardActions(onSearch = { ... })`.
   - Wired `searchCoordinator.clear()` to TopAppBar search toggle when closing search mode (`if (!nextMode) searchCoordinator.clear()`).
   - Wired `searchCoordinator.dispose()` to `DisposableEffect(Unit)` on screen exit.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 10 methods in `ReaderSearchSecurityTest.kt`: Clear invalidates generation and rejects delayed results (`testClearInvalidatesGenerationAndRejectsDelayedResults`); Clear cancels active in-flight job (`testClearCancelsActiveInFlightJob`); Query change cancels active job and rejects late completion (`testQueryChangedCancelsInFlightSearchAndRejectsLateCompletion`); Subsequent search supersedes in-flight search (`testSubsequentSearchSupersedesInFlightSearch`); Blank query rejected without invoking searcher or scrolling (`testBlankQueryRejectedWithoutInvokingSearcherOrScrolling`); Delayed error suppressed when search cleared (`testDelayedErrorSuppressedWhenSearchCleared`); Delayed error reported when search still current (`testDelayedErrorReportedWhenSearchStillCurrent`); Match navigation wrapping (`testMatchNavigationWrapping`); Dispose cancels job and invalidates generation (`testDisposeCancelsJobAndInvalidatesGeneration`); State changed callback invoked on all mutations (`testStateChangedCallbackInvokedOnAllMutations`) | `app-host/src/test/java/com/pdfchemy/app/security/ReaderSearchSecurityTest.kt` |
| Full App-Host Unit Suite | PASS: 86 unit tests in `:app-host:testDebugUnitTest` | Gradle task log |
| IPC Module Unit Suite | PASS: 8 unit tests in `:pdf-ipc:testDebugUnitTest` | Gradle task log |
| Android Test APK Assembly | PASS: `:app-host:assembleDebugAndroidTest` | Gradle task log |
