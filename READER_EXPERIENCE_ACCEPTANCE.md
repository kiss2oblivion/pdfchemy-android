# PDFchemy Reader Experience — Next Development Cycle Acceptance Report (`R01`–`R06`)

**Repository:** `kiss2oblivion/pdfchemy-android`
**Branch:** `gemini/next-reader-experience`
**Pull Request:** https://github.com/kiss2oblivion/pdfchemy-android/pull/15
**Base Branch:** `main` (`4b3c157f3458f7df00ea94044258d33c7449ced9`)
**Worktree:** `E:\backup_ext_hdd\android-projects-2026-07-30\reader-next-cycle`
**Scope:** `:app-host` & root documentation only (zero modifications to `:pdf-ipc`, `:pdf-jail`, `:pdf-renderer`, Billing, Ads, Deskew, or Office export engines)

---

## 1. Repository & Release Isolation Verification

- **2.1.0 Play Acceptance Worktree (`E:\backup_ext_hdd\android-projects-2026-07-30\release-validation-2.1.0-2026-10-23\play-acceptance`):**
  - Verified clean working tree (`git status --short` empty).
  - Branch `gemini/2.1.0-play-acceptance` remains untouched at commit `31f744e0ccd7fa4b8eddf439a0c837e8d88eca36` (matching `repo-android/gemini/2.1.0-play-acceptance`).
  - Codex PRs (`#13`, `#14`) and the `2.1.0` release candidate were not modified or interrupted.
- **PR #12 GitHub CI Status (`31f744e0ccd7fa4b8eddf439a0c837e8d88eca36`):**
  - `architecture`: `SUCCESS`
  - `secrets`: `SUCCESS`
  - `dependency-security`: `SUCCESS`
  - `tests`: `SUCCESS` (run `40867756273`; parallel run `40867748095` reported `FAILURE`)
  - `android-instrumented-security (24, 30, 34, 36)`: `FAILURE`
  - `build`: `SKIPPED`

---

## 2. Implemented Commits (`R01`–`R06` & Remediation)

| Task | Commit SHA | Summary | Regression Test Suite | Status |
| :--- | :--- | :--- | :--- | :--- |
| **R01** | `47bf3d78` | `feat(reader): R01 - implement consistent Reader-first navigation and tool continuity` | `ReaderNavigationTest`, `ScreenRestorationTest` | ✅ Accepted |
| **R02** | `26a80f82` | `perf(reader): R02 - enable native inertial scrolling, smooth zoom, and bounded page bitmap cache` | `ReaderViewportAndCacheTest` | ✅ Accepted |
| **R03** | `6594c6d6` | `feat(reader): R03 - refine tap-to-reveal overlay controls with stable viewport padding` | `ReaderControlsStateTest` | ✅ Accepted |
| **R04** | `32cc3928` | `feat(reader): R04 - expand Reader command centre for 1-2 tap access to core PDF tools` | `ReaderCommandCentreTest` | ✅ Accepted |
| **R05** | `cd435d8d` | `fix(reader): R05 - stabilize document identity, restore exact scroll offset, and handle revoked SAF access` | `ReaderHistoryAndPositionTest` | ✅ Accepted |
| **R06** | `55965e65` | `fix(reader): R06 - harden accessibility semantics, small-screen landscape layout, font scaling, and error recovery` | `ReaderAccessibilityAndResilienceTest` | ✅ Accepted |
| **Docs** | `d336eb13` | `docs(reader): add READER_EXPERIENCE_ACCEPTANCE.md for R01-R06 cycle` | CI Run `40885512390` (8/9 jobs passed) | ✅ Superseded by Lint Remediation |
| **Lint Remediation** | `HEAD` (`gemini/next-reader-experience`) | `fix(reader): resolve release lint failures and validate R01-R06` | `ReaderAccessibilityAndResilienceTest` (20-locale matrix) + `:app-host:lintRelease` (`0 errors`) | ✅ Verified |

---

## 3. Task-by-Task Before / After Behavior & Architectural Details

### Task R01 — Reader Entry and Navigation (`47bf3d78`)
- **Before:**
  - Opening a PDF from Recent Files routed to `Screen.CompressPdf` instead of the Reader.
  - Selecting `PDF Reader Studio` in `ToolSearch` passed `Screen.PdfReader(Uri.EMPTY)`, dropping any active continuity document.
  - Launching a tool from the Reader and pressing Back returned to `Screen.Home` instead of returning to the Reader with the active document preserved.
  - Several tools (`SplitPdf`, `RotatePdf`, `DeletePages`, `PageOrganizer`, `FillForm`, `QuickFillSign`, `TableExtractor`, `OfficeExport`) ignored `continuityDocumentUri` and forced the user to re-pick the same PDF.
- **After:**
  - Introduced `ReaderNavigationCoordinator` (`app-host/src/main/java/com/pdfchemy/app/logic/ReaderNavigationCoordinator.kt`) integrated with `MainViewModel` and `MainActivity`.
  - Opening a PDF from Home, Recent Files, SAF picker, Tool Search, or external `ACTION_VIEW` / `ACTION_SEND` PDF intents consistently opens `Screen.PdfReader` with the resolved URI.
  - Transitioning from `PdfReader` to any tool records `readerReturnUri` and seeds `continuityDocumentUri`; pressing Back from that tool returns directly to `Screen.PdfReader(activeUri)` with the document intact.
  - Updated `OrganizeScreens.kt`, `PageOrganizerScreen.kt`, `AcroFormScreens.kt`, `QuickFillSignScreen.kt`, `TableExtractorScreen.kt`, `OfficeExportScreen.kt`, `ToolSearch.kt`, and `PdfReaderScreen.kt`.

### Task R02 — Continuous Scrolling and Zoom (`26a80f82`)
- **Before:**
  - `LazyColumn` had `userScrollEnabled = false`, and all vertical scrolling was manually emulated via `detectTransformGestures`, eliminating native inertial fling scrolling and causing jitter during single-finger drags.
  - Every `PdfReaderPageItem` rendered an isolated bitmap in `LaunchedEffect` and immediately called `bitmap.recycle()` on disposal, causing constant IPC re-rendering when scrolling back and forth across page boundaries.
  - Stale or superseded render requests had no per-page token cancellation gate.
- **After:**
  - Created `ReaderViewportController.kt` containing `BoundedPageBitmapCache`, `ReaderRenderGate`, and `ReaderZoomCalculator`.
  - Native `LazyColumn` inertial fling scrolling is enabled at `1.0x` zoom (`ReaderZoomCalculator.isNativeScrollEnabled(scale, activePointerCount)`), while multi-touch pinch-zoom (`1.0x`–`5.0x`), focal double-tap zoom (`2.5x`), Fit Width / Fit Page toggle, and zoomed pan-with-edge-scroll-overflow are handled smoothly via a custom `awaitEachGesture` pointer pass.
  - `BoundedPageBitmapCache` caches rendered page bitmaps in an LRU structure with reference counting (`acquire`, `putAndAcquire`, `release`, `clear`), preventing premature `Bitmap.recycle()` while a Composable is still drawing and deterministically recycling bitmaps once evicted and unreferenced.
  - `ReaderRenderGate` assigns monotonic sequence tokens per page and closes on Reader disposal so stale renders are immediately recycled and never overwrite current pages.

### Task R03 — Minimal Reader Controls (`6594c6d6`)
- **Before:**
  - `LazyColumn` `contentPadding` switched dynamically between `80.dp`/`120.dp` when `showControls == true` and `16.dp` when `showControls == false`, causing a `64dp` vertical jump of the entire document whenever the user tapped to hide or reveal controls.
  - `showControls` and `isSearchMode` used transient `remember` and reset on device rotation.
  - Bookmarks (`OutlineBookmark`) were extracted into memory, but no Table of Contents UI existed to view or jump to them.
- **After:**
  - Created `ReaderControlsController.kt` (`ReaderControlsUiState`, `ReaderBackAction`, `ReaderViewportPaddingDp`).
  - `ReaderControlsUiState.computeStableViewportPadding(isLandscape)` supplies orientation-aware constant viewport padding independent of `showControls`, eliminating scroll jumps when toggling immersive mode.
  - Persisted `showControls`, `isSearchMode`, `showTocSheet`, `showCommandCentreSheet`, `searchQuery`, and `currentMatchIndex` across orientation changes via `rememberSaveable`.
  - Added a non-intrusive floating compact page pill (`X / Y`) in immersive mode, a floating zoom reset chip (`2.5x • Reset`) when zoomed, a Table of Contents `ModalBottomSheet` for chapter navigation, and deterministic Back handling (`CLOSE_COMMAND_CENTRE` → `CLOSE_TOC` → `CLOSE_SEARCH` → `RESET_ZOOM` → `EXIT_READER`).

### Task R04 — Tool Accessibility / Reader Command Centre (`32cc3928`)
- **Before:**
  - Only 6 tools were reachable from the Reader (`Reflow`, `Edit`, `Compress`, `OCR`, `Sign`, `Protect`); core operations (`Split`, `Page Organizer`, `Rotate`, `Delete Pages`, `Fill Form`, `Quick Fill & Sign`, `Extract Tables`, `Office Export`, `Redact`, `Watermark`, `Page Numbers`) required exiting the Reader, navigating Home, and re-selecting the document.
- **After:**
  - Added `ReaderCommandAction` and `ReaderCommandCentreCatalog` in `ReaderControlsController.kt`, exposing 17 core PDF tools in 1–2 taps from the Reader (1 tap for Top/Bottom bar actions, 2 taps via the `All Tools` Command Centre `ModalBottomSheet`).
  - Wired `RedactionScreen.kt`, `WatermarkScreen.kt`, and `PageNumberScreen.kt` to auto-populate from `viewModel.continuityDocumentUri` so all 17 Command Centre actions open with the active Reader document pre-selected.

### Task R05 — Document History and Restoration (`cd435d8d`)
- **Before:**
  - `DocumentIdentity.computeStableId` produced different hashes when the same PDF was staged into different temporary snapshots because `file://` staging paths or Windows/Android path separators leaked into the fallback identity calculation.
  - Only `pageIndex` was saved; `firstVisibleItemScrollOffset` was lost, and saved positions were not clamped if a document was later shortened.
  - Opening a PDF directly into the Reader did not record it in Recent Files (`HistoryRepository`), and revoked SAF permissions or deleted files left dead entries in Recent Files.
- **After:**
  - Normalized `DocumentIdentity.computeStableId` across staged `file://` snapshots and path separators (`/`, `\`, `:`) using clean filename + exact byte length + head/tail 64 KiB SHA-256 sampling.
  - Added `ReaderSavedPosition(pageIndex, scrollOffsetPx)` with `saveReaderPosition` and `getSavedReaderPosition` clamping to `0 until totalPages`, restoring exact sub-page scroll offsets.
  - Added `DocumentIdentity.classifyLoadException` (`REVOKED_OR_INACCESSIBLE` vs `CORRUPTED_OR_INVALID`), `HistoryRepository.removeHistoryItem`, and `MainViewModel.recordReaderHistory`.
  - When a recent document fails due to revoked SAF access or file deletion, the stale entry is automatically pruned from `HistoryRepository` and the user receives a clear `File Inaccessible` dialog (`vanguard_provider_error_title`); damaged/truncated PDFs route cleanly to the `Damaged PDF` recovery dialog with 1-tap `Repair PDF` continuity.

### Task R06 — Accessibility and Interface Maturity (`55965e65`)
- **Before:**
  - Several Reader controls (`Back`, `Fit Width`/`Fit Page`, `Search`, `Table of Contents`, `Previous Match`, `Next Match`, `Page X`, `X of Y`) used hardcoded English strings.
  - Bottom command bar buttons lacked minimum `48dp` touch target constraints, explicit merged button semantics, and horizontal scroll fallback for small screens (`320dp`), landscape mode, or `200%` system font scale.
  - `BoundedPageBitmapCache` did not subscribe to Android `ComponentCallbacks2` memory pressure callbacks, and per-page render failures left an indefinite spinner with no retry path.
- **After:**
  - Localized all Reader UI strings and format specifiers in `strings.xml` (`reader_action_search`, `reader_toc_title`, `reader_fit_width`, `reader_fit_page`, `reader_search_hint`, `reader_action_clear_search`, `reader_search_no_matches`, `reader_page_indicator`, `reader_page_badge_compact`, `reader_page_content_desc`, `reader_reset_zoom_fmt`, `previous_match`, `next_match`) across `values/strings.xml` and all 20 localized `values-*/strings.xml` directories.
  - Enforced `48dp` minimum touch targets (`ReaderAccessibilityLayout.MIN_TOUCH_TARGET_DP`), explicit `Role.Button` + merged `contentDescription` semantics, `navigationBarsPadding()`, and adaptive horizontal scrolling for small screens (`<= 360dp`), landscape orientation, and `>= 1.3x` / `2.0x` font scaling.
  - Added `BoundedPageBitmapCache.computeAdaptiveBudget` and `onTrimMemory(level)` wired via `ComponentCallbacks2`, plus explicit initial document loading state, "No matches found" search state, and per-page render failure recovery (`Retry` button).

---

## 4. Memory & Performance Measurements

| Metric / Mechanism | Before (`main`) | After (`gemini/next-reader-experience`) | Verification Evidence |
| :--- | :--- | :--- | :--- |
| **Single-Finger Scroll Path (`1.0x`)** | `LazyColumn(userScrollEnabled = false)` + manual `detectTransformGestures` pan emulation (no fling physics) | Native `LazyColumn(userScrollEnabled = true)` with hardware-accelerated Compose fling physics | `ReaderViewportAndCacheTest`: `native scroll is enabled at 1x zoom and disabled when zoomed or multi-touching` |
| **Viewport Jump on Control Toggle** | `64dp` vertical jump (`topPadding` changed from `80dp` to `16dp` on tap) | `0dp` jump (constant orientation-specific `contentPadding`: `80dp`/`120dp` portrait, `64dp`/`96dp` landscape) | `ReaderControlsStateTest`: `viewport padding remains strictly identical when controls are shown or hidden` |
| **Bitmap Cache Retention (Standard Heap)** | Uncached per-item render; every scroll across boundary re-invoked `NativeRendererCoordinator.renderUriToBitmap` | Bounded LRU cache: max `6` pages / `32 MiB` (`computeAdaptiveBudget` for `512 MiB` heap); cache hits avoid IPC re-rendering | `ReaderViewportAndCacheTest`: `bitmap cache reuses cached pages and evicts oldest unreferenced entries within budget` |
| **Bitmap Cache Retention (Low-RAM / `<= 128 MiB` Heap)** | Unbounded per-composition allocation | Adaptive budget reduces cap to max `3` pages / `12 MiB`; `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` evicts all unreferenced pages immediately | `ReaderAccessibilityAndResilienceTest`: `adaptive cache budget scales down on low RAM or constrained heaps` & `onTrimMemory evicts unreferenced bitmaps while protecting active displayed pages` |
| **Active Bitmap Safety During Eviction** | Immediate `bitmap.recycle()` in `DisposableEffect` | Reference-counted retirement (`pendingRecycle`): evicted bitmaps with active UI references are never recycled until `release()` drops refCount to `0` | `ReaderViewportAndCacheTest`: `bitmap cache defers recycling of evicted bitmap until active UI reference is released` |
| **Stale Render Suppression** | In-flight renders could complete after rapid scroll or exit | `ReaderRenderGate` invalidates superseded page tokens and recycles stale bitmaps immediately upon completion | `ReaderViewportAndCacheTest`: `render gate invalidates superseded page tokens and blocks all tokens after close` |

---

## 5. Release Lint Remediation Report (PR #15 CI Run `40885512390`)

### A. Complete Accounting of All 14 Original Lint Errors

On commit `d336eb13d1cccc9ff987728e2c60b77fc88a9caa` (CI run https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40885512390), 8 of 9 CI jobs (`secrets`, `architecture`, `dependency-security`, `tests`, and all 4 `android-instrumented-security` matrix jobs on API 24, 30, 34, and 36) passed. The `build` job failed in `:app-host:lintRelease` with **14 errors** (`0` security, API, accessibility, or resource-integrity errors; all **14** were `MissingTranslation` errors in `app-host/src/main/res/values/strings.xml` lines 1156–1169):

| # | Lint ID | Classification | Source Location | String Key | Default Value (`values/strings.xml`) | Resolution |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1156` | `menu_more_tools` | `All Tools` | Translated across all 20 `values-*` locale directories |
| 2 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1157` | `reader_action_search` | `Search Document` | Translated across all 20 `values-*` locale directories |
| 3 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1158` | `reader_toc_title` | `Table of Contents` | Translated across all 20 `values-*` locale directories |
| 4 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1159` | `reader_fit_width` | `Fit Width` | Translated across all 20 `values-*` locale directories |
| 5 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1160` | `reader_fit_page` | `Fit Page` | Translated across all 20 `values-*` locale directories |
| 6 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1161` | `reader_search_hint` | `Search document…` | Translated across all 20 `values-*` locale directories |
| 7 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1162` | `reader_action_clear_search` | `Clear Search` | Translated across all 20 `values-*` locale directories |
| 8 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1163` | `reader_search_no_matches` | `No matches found` | Translated across all 20 `values-*` locale directories |
| 9 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1164` | `reader_page_indicator` | `Page %1$d of %2$d` | Translated across all 20 `values-*` locale directories preserving `%1$d` and `%2$d` |
| 10 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1165` | `reader_page_badge_compact` | `%1$d / %2$d` | Added across all 20 `values-*` locale directories preserving `%1$d` and `%2$d` |
| 11 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1166` | `reader_page_content_desc` | `Page %1$d` | Translated across all 20 `values-*` locale directories preserving `%1$d` |
| 12 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1167` | `reader_reset_zoom_fmt` | `%1$.1fx • Reset` | Translated across all 20 `values-*` locale directories preserving `%1$.1f` |
| 13 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1168` | `previous_match` | `Previous Match` | Translated across all 20 `values-*` locale directories |
| 14 | `MissingTranslation` | Resource localization | `app-host/src/main/res/values/strings.xml:1169` | `next_match` | `Next Match` | Translated across all 20 `values-*` locale directories |

### B. Files Changed During Remediation

- `app-host/src/main/res/values-ar/strings.xml`
- `app-host/src/main/res/values-de/strings.xml`
- `app-host/src/main/res/values-es/strings.xml`
- `app-host/src/main/res/values-fr/strings.xml`
- `app-host/src/main/res/values-hi/strings.xml`
- `app-host/src/main/res/values-in/strings.xml`
- `app-host/src/main/res/values-it/strings.xml`
- `app-host/src/main/res/values-ja/strings.xml`
- `app-host/src/main/res/values-ko/strings.xml`
- `app-host/src/main/res/values-nl/strings.xml`
- `app-host/src/main/res/values-pl/strings.xml`
- `app-host/src/main/res/values-pt/strings.xml`
- `app-host/src/main/res/values-pt-rBR/strings.xml`
- `app-host/src/main/res/values-ro/strings.xml`
- `app-host/src/main/res/values-ru/strings.xml`
- `app-host/src/main/res/values-th/strings.xml`
- `app-host/src/main/res/values-tr/strings.xml`
- `app-host/src/main/res/values-vi/strings.xml`
- `app-host/src/main/res/values-zh-rCN/strings.xml`
- `app-host/src/main/res/values-zh-rTW/strings.xml`
- `app-host/src/test/java/com/pdfchemy/app/ui/ReaderAccessibilityAndResilienceTest.kt`
- `READER_EXPERIENCE_ACCEPTANCE.md`

### C. Commands Executed and Actual Validation Results

| Verification Step | Command Executed | Actual Result |
| :--- | :--- | :--- |
| **Release Lint & APK Build** | `./gradlew :app-host:lintRelease :app-host:assembleDebug :app-host:assembleRelease --max-workers=2 "-Pkotlin.compiler.execution.strategy=in-process" "-PunsignedReleaseVerification=true" --stacktrace` | `BUILD SUCCESSFUL` (`:app-host:lintReportRelease`: `0 errors, 626 warnings`) |
| **Release Gate & Artifact Hygiene** | `python scripts/check_release_gate.py --artifact` | `PASS` (`corpus_files: 14`, `smoke_routes: 61`, `fixture_hashes: PASS`, `production_ad_ids: PASS`, `artifact_hygiene: PASS`) |
| **Secrets Audit** | `python -m unittest discover -s tools -p test_check_secrets.py && python tools/check_secrets.py` | `Ran 10 tests in 0.001s — OK`; `TruffleHog 3.97.9: 6 findings; 6 exact reviewed historical matches; 0 rejected` |
| **Security Architecture & OSV Audit** | `./gradlew :app-host:securityAudit securityArchitecture dependencyInventory && python tools/check_dependencies.py` | `BUILD SUCCESSFUL`; `securityAudit scanned 122 files`; `Scanned 234 resolved production dependencies; 0 affected packages` |
| **R01–R06 & 20-Locale Unit Tests** | `./gradlew :app-host:testDebugUnitTest --tests "com.pdfchemy.app.ui.ReaderAccessibilityAndResilienceTest" --tests "com.pdfchemy.app.ui.ReaderNavigationTest" --tests "com.pdfchemy.app.ui.ReaderViewportAndCacheTest" --tests "com.pdfchemy.app.ui.ReaderControlsStateTest" --tests "com.pdfchemy.app.ui.ReaderCommandCentreTest" --tests "com.pdfchemy.app.logic.ReaderHistoryAndPositionTest"` | `BUILD SUCCESSFUL` |

---

## 6. Remaining Known Limitations & Next-Cycle Opportunities (Ranked by Severity)

1. **Low Severity — In-Page Visual Highlighting of Search Occurrences:**
   - *Current State:* In-document search (`PdfFindAndReplaceEngine.findOccurrences`) accurately locates matches and scrolls directly to the matching page (`listState.animateScrollToItem`) with `current/total` navigation and "No matches found" feedback, but does not draw yellow bounding-box overlays on the rasterized page bitmap.
   - *Next-Cycle Recommendation:* Project PDFBox text bounding coordinates onto the `PdfReaderPageItem` canvas overlay for visual term highlighting.
2. **Low Severity — Nested Bookmark Indentation in Table of Contents Sheet:**
   - *Current State:* Top-level outline bookmarks are listed and navigable in the Table of Contents `ModalBottomSheet`. Deeply nested `OutlineBookmark.children` trees can be flattened with indentation padding in a future enhancement.
3. **Low Severity — Continuous Page Thumbnail Scrub Bar:**
   - *Current State:* Users can jump via Table of Contents, Search, or fast inertial fling scrolling. Adding a draggable fast-scroller thumb for 500+ page manuals is a candidate for a future cycle.
