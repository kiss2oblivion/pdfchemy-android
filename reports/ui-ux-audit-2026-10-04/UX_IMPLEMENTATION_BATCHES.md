# UI/UX implementation batches

Base: `repo-android/main` at `4b3c157f3458f7df00ea94044258d33c7449ced9`.
Branch: `codex/ux-audit-resume-2026-10-07`. Push target is exclusively
`kiss2oblivion/pdfchemy-android`; no merge is performed.

## Batch 1 — tool discovery and reading entry

Closed implementation IDs: UX-01, UX-02.
UX-03 remains PARTIAL: duplicate Quick Fill was removed; category/scan naming
still requires reconciliation.

Still-open IDs after Batch 1: UX-03, UX-04, UX-05, UX-06, UX-07, UX-08, UX-09,
UX-10, UX-13, UX-14, UX-15, UX-16, UX-17, UX-18, UX-20, UX-21, UX-22, UX-23,
UX-25, UX-26, UX-27, UX-28, UX-29, UX-30, UX-31, UX-33, UX-34, UX-35, UX-36,
UX-37, UX-38, UX-40, UX-41, UX-42, UX-43, UX-44, UX-47, UX-48, UX-49, UX-50.

The existing registry now covers all 54 feature types, including three Office
formats (56 entries). Full names and aliases take precedence over incidental
description matches, preserving conversion direction. Search has deterministic
ordering, locale-independent case normalization, and an empty-results message.
OCR, text extraction, page-image conversion and embedded-image extraction have
separate destinations. Audio export remains part of the existing reflow route.
Home opens the existing guarded PDF picker directly. A Reader search route with
no URI provides guarded selection instead of loading Uri.EMPTY. Existing Reader
capabilities, architecture, security guards, Audio Export and Vanguard are retained.

Exact files changed:

- `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/OrganizeScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ToolSearch.kt`
- `app-host/src/test/java/com/pdfchemy/app/ui/ToolRegistryTest.kt`
- `app-host/src/main/res/values/strings.xml`
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
- `reports/ui-ux-audit-2026-10-04/UX_CURRENT_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_IMPLEMENTATION_BATCHES.md`

New search copy uses English locale fallbacks consistent with the merged
translation policy. Dedicated translations remain polish work.

Validation: registry and navigation restoration tests pass (6 tests, zero
failures), `:app-host:assembleDebug` passes, `:app-host:lintDebug` passes (626
warnings, zero fatal errors), and `git diff --check` passes. Command:
`gradlew :app-host:testDebugUnitTest --tests com.pdfchemy.app.ui.ToolRegistryTest
--tests com.pdfchemy.app.ui.ScreenRestorationTest :app-host:assembleDebug
:app-host:lintDebug --console=plain` (BUILD SUCCESSFUL, 3m 48s).
An initial icon-import compile failure, search conversion test failures and one
lint internal analysis failure were corrected/rechecked before this commit.
Device gestures, TalkBack and provider runtime checks
are not inferred from unit tests. The audit remains unfinished.

Batch 1 remote verified: `313a3577f930b58bdbe863b388e599c50162ccb6`.
CI: https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37629795590
(dispatched against that exact SHA; ongoing at Batch 2 preparation).

## Batch 2 — document input and page control

Closed implementation IDs: UX-04, UX-13, UX-33.
UX-23 remains PARTIAL: the concrete caller-context bitmap decode defect is
fixed, but device profiling is still outstanding.

Still-open IDs after Batch 2: UX-03, UX-05, UX-06, UX-07, UX-08, UX-09, UX-10,
UX-14, UX-15, UX-16, UX-17, UX-18, UX-20, UX-21, UX-22, UX-23, UX-25, UX-26,
UX-27, UX-28, UX-29, UX-30, UX-31, UX-34, UX-35, UX-36, UX-37, UX-38, UX-40,
UX-41, UX-42, UX-43, UX-44, UX-47, UX-48, UX-49, UX-50.

The safeguard stores its actual analyzed source URI and sends it to Split in
the existing sealed navigation model. Saved navigation retains that URI;
legacy saved Split routes still restore. No picker/storage/security bypass is
introduced. Images-to-PDF deduplicates repeated gallery/camera selections.

Organizer adds a validated Move To position dialog, retaining adjacent arrows
and chronological undo/redo. A 20-to-1 move is one session action. The UI now
observes the session revision during composition so selection, previews and
history updates actually refresh. Copy no longer promises unimplemented drag.

NativeRendererCoordinator's URI render path now wraps staging, file IO,
PixelWire decoding, cleanup and lease handling in Dispatchers.IO. Its security
limits, staged identity, service boundary and fail-closed checks are unchanged.
This is a concrete UI responsiveness defect, not security-audit work.

Exact files changed:

- `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/OrganizerSession.kt`
- `app-host/src/main/java/com/pdfchemy/app/sandbox/NativeRendererCoordinator.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/CreateScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/OrganizeScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PageOrganizerScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ToolSearch.kt`
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
- `app-host/src/main/res/values-pt-rBR/strings.xml`
- `app-host/src/main/res/values-pt/strings.xml`
- `app-host/src/main/res/values-ro/strings.xml`
- `app-host/src/main/res/values-ru/strings.xml`
- `app-host/src/main/res/values-th/strings.xml`
- `app-host/src/main/res/values-tr/strings.xml`
- `app-host/src/main/res/values-vi/strings.xml`
- `app-host/src/main/res/values-zh-rCN/strings.xml`
- `app-host/src/main/res/values-zh-rTW/strings.xml`
- `app-host/src/main/res/values/strings.xml`
- `app-host/src/test/java/com/pdfchemy/app/logic/OrganizerSessionTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/ui/ScreenRestorationTest.kt`
- `reports/ui-ux-audit-2026-10-04/UX_CURRENT_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_IMPLEMENTATION_BATCHES.md`

Validation: 23 focused tests pass (OrganizerSessionTest 9, ScreenRestorationTest
3, ToolRegistryTest 4, ReleaseWorkflowRegressionTest 7); debug assembly and
debug lint pass, and `git diff --check` passes. Final command:
`gradlew :app-host:testDebugUnitTest --tests com.pdfchemy.app.logic.OrganizerSessionTest
--tests com.pdfchemy.app.ui.ScreenRestorationTest --tests com.pdfchemy.app.ui.ToolRegistryTest
--tests com.pdfchemy.app.logic.ReleaseWorkflowRegressionTest :app-host:assembleDebug
:app-host:lintDebug --console=plain` (BUILD SUCCESSFUL, 5m 11s).
This recheck includes the final Organizer composition-observation correction.
No Android devices were attached (`adb devices -l`); device profiling remains
unverified, not silently deferred or counted as complete.

Batch 2 remote verified: `fb212fb6cb6cb321089dff4f2c08e7b5384729dc`.

## Batch 3 — predictable preferences and accessible controls

Closed implementation IDs: UX-26, UX-42, UX-43, UX-44, UX-49, UX-50.
UX-48 remains PARTIAL: label sizing is corrected in source, but small-width and
large-font device verification remains outstanding.

Still-open IDs after Batch 3: UX-03, UX-05, UX-06, UX-07, UX-08, UX-09, UX-10,
UX-14, UX-15, UX-16, UX-17, UX-18, UX-20, UX-21, UX-22, UX-23, UX-25, UX-27,
UX-28, UX-29, UX-30, UX-31, UX-34, UX-35, UX-36, UX-37, UX-38, UX-40, UX-41,
UX-47, UX-48.

Settings and Premium use the same Back contract for system/toolbar navigation.
The existing haptics toggle gates the shared Compose delegate, including tools
that already call LocalHapticFeedback; category/tool cards no longer bypass it.
Home theme selection exposes System/Light/Dark choices with selected semantics
and current-state announcement. Existing palette, cards and theme options remain.

Reflow's deliberate theme/font/serif defaults persist locally and can be reset
from its existing menu without deleting reading positions or privacy choices.
Audio Export and its lifecycle remain untouched. Theme choices use a shared
48dp named/selectable target in both reading layouts; editor color choices are
named/selectable 48dp targets in a scrollable row, preserving access on narrow
screens. Category height scales for enlarged fonts; tool cards wrap labels with
flexible height. The Vanguard badge's percent escaping is corrected only in
copy; no security implementation is changed.

Exact files changed:

- `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ChoiceControls.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PdfEditorScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PreferenceHaptics.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ReaderDefaultsStore.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ReflowReaderScreen.kt`
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
- `app-host/src/main/res/values-pt-rBR/strings.xml`
- `app-host/src/main/res/values-pt/strings.xml`
- `app-host/src/main/res/values-ro/strings.xml`
- `app-host/src/main/res/values-ru/strings.xml`
- `app-host/src/main/res/values-th/strings.xml`
- `app-host/src/main/res/values-tr/strings.xml`
- `app-host/src/main/res/values-vi/strings.xml`
- `app-host/src/main/res/values-zh-rCN/strings.xml`
- `app-host/src/main/res/values-zh-rTW/strings.xml`
- `app-host/src/main/res/values/strings.xml`
- `app-host/src/test/java/com/pdfchemy/app/ui/PreferenceHapticsTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/ui/ReaderDefaultsStoreTest.kt`
- `reports/ui-ux-audit-2026-10-04/UX_BASELINE_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_CURRENT_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_IMPLEMENTATION_BATCHES.md`

Validation: all 104 app-host tests pass, with zero failures/errors/skips,
including preference persistence/reset and haptic delegation. Debug assembly
and debug lint pass (630 warnings, zero fatal errors); `git diff --check`
passes. Command: `gradlew :app-host:testDebugUnitTest :app-host:assembleDebug
:app-host:lintDebug --console=plain` (BUILD SUCCESSFUL, 6m 48s). The initial
missing scroll imports were fixed before this successful recheck.
No physical TalkBack, font-scale or haptic-device results are claimed.

Batch 3 remote verified: `9b9c1222b2a8a1bbc23d0ab34e8883fb9d76a03c`.
CI: https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37636867643
(head SHA verified; architecture, secret/dependency checks and tests passed;
emulator jobs were ongoing at Batch 4 preparation).

## Batch 4 — asynchronous input/result correctness

Closed implementation IDs: UX-35, UX-37.
UX-38 remains PARTIAL: current-page publication and initial handoff rendering
are corrected, but signature-session undo remains open. UX-18 also remains
PARTIAL because returning to the preserved Reader session is still missing.

Still-open IDs after Batch 4: UX-03, UX-05, UX-06, UX-07, UX-08, UX-09, UX-10,
UX-14, UX-15, UX-16, UX-17, UX-18, UX-20, UX-21, UX-22, UX-23, UX-25, UX-27,
UX-28, UX-29, UX-30, UX-31, UX-34, UX-36, UX-38, UX-40, UX-41, UX-47, UX-48.

Compression analysis has a publication generation and cancels superseded input
jobs. A late recommendation only changes settings the user has not chosen;
failure has no authority to reset explicit choices. The same rule applies to
the existing batch-analysis path. Recurring settings are saved only through
the explicit My default action in the existing compression app bar; reset is
scoped to those settings. Changing a per-document slider/toggle does not
silently create a persistent profile. Existing task/memory safeguards remain.
Dismissal preserves the chosen controls; fresh batch input reloads saved
defaults. A reset invalidates pending analysis, including batch publication.

Redaction ties results to request, URI, query and regex mode. Editing the query
or selecting another input clears old targets; out-of-order responses cannot
publish. Failure is visibly distinct from zero matches, with a Search retry.
Forensic sanitization and security/worker boundaries are untouched.

Signing clears the previous preview and publishes only the latest URI/page
request's count and bitmap. Unpublished bitmaps are recycled and cancellation
is preserved. An initial URI passed from Reader is now rendered without
forcing another picker. Existing PKI recovery is retained.

Exact files changed:

- `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/CompressionDefaultsStore.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/LatestRequest.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/RedactionScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/SignPdfScreen.kt`
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
- `app-host/src/main/res/values-pt-rBR/strings.xml`
- `app-host/src/main/res/values-pt/strings.xml`
- `app-host/src/main/res/values-ro/strings.xml`
- `app-host/src/main/res/values-ru/strings.xml`
- `app-host/src/main/res/values-th/strings.xml`
- `app-host/src/main/res/values-tr/strings.xml`
- `app-host/src/main/res/values-vi/strings.xml`
- `app-host/src/main/res/values-zh-rCN/strings.xml`
- `app-host/src/main/res/values-zh-rTW/strings.xml`
- `app-host/src/main/res/values/strings.xml`
- `app-host/src/test/java/com/pdfchemy/app/logic/CompressionDefaultsStoreTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/LatestRequestTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/ui/CompressionAnalysisChoiceTest.kt`
- `reports/ui-ux-audit-2026-10-04/UX_CURRENT_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_IMPLEMENTATION_BATCHES.md`

Validation: all 12 focused tests pass (LatestRequest, CompressionDefaultsStore,
CompressionAnalysisChoice and ReleaseWorkflowRegression), zero failures/errors.
Debug assembly and debug lint pass (zero errors, 632 warnings).
`git diff --check` passes. Command: `gradlew :app-host:testDebugUnitTest
--tests com.pdfchemy.app.logic.LatestRequestTest
--tests com.pdfchemy.app.logic.CompressionDefaultsStoreTest
--tests com.pdfchemy.app.ui.CompressionAnalysisChoiceTest
--tests com.pdfchemy.app.logic.ReleaseWorkflowRegressionTest
:app-host:assembleDebug :app-host:lintDebug --console=plain`
(BUILD SUCCESSFUL, 6m 9s). The final recheck includes the dismissal/batch regression.
An incorrect helper name was corrected before this successful recheck.
Physical signing/rendering and provider failure injection remain unverified.

Current matrix after this batch: 21 DONE, 17 PARTIAL, 12 STILL OPEN,
zero SUPERSEDED. The main reconciliation table contains all 50 latest
dispositions, with previous batch evidence retained below it.


## Batch 5 — Reader navigation, document space and return continuity

Native Reader now exposes working nested outline navigation, validated page jump, lazy page previews, local personal bookmarks, fit-page/fit-width modes, share and print. Hidden chrome releases its reserved document padding. Original Reader URI and in-memory page/offset survive tool handoffs and return, including when persistent position storage is disabled. Reflow counts actual occurrences, brings the active glyph into view, preserves nested outline targets and hides its controls. Provider/page failures expose retry separately from executable/encrypted/damaged Vanguard results. Existing renderer and security boundaries remain. Batch 4 remote SHA was verified as ca4091022ba4cfb4a13a42dd090ffc9c575e1e19; its CI run 37641926774 failed overall despite successful architecture/dependency/secrets jobs; remaining job evidence was unavailable through the current log API (403), so full CI success is not claimed.

Closed source IDs: UX-15, UX-16, UX-18, UX-27.

Still open or partial: UX-03, UX-05, UX-06, UX-07, UX-08, UX-09, UX-10, UX-14, UX-17, UX-20, UX-21, UX-22, UX-23, UX-25, UX-28, UX-29, UX-30, UX-31, UX-34, UX-36, UX-38, UX-40, UX-41, UX-47, UX-48.

Exact files changed:

- `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/DocumentActions.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/ReaderNavigation.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ReflowReaderScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/utils/DocumentIdentity.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/ReaderNavigationTest.kt`
- `reports/ui-ux-audit-2026-10-04/UX_CURRENT_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_IMPLEMENTATION_BATCHES.md`

Nine focused tests pass (ReaderNavigation and ReleaseWorkflowRegression), zero failures/errors. Debug assembly and lint pass; lint reports zero errors and 634 warnings. Command: gradlew :app-host:testDebugUnitTest --tests com.pdfchemy.app.logic.ReaderNavigationTest --tests com.pdfchemy.app.logic.ReleaseWorkflowRegressionTest :app-host:assembleDebug :app-host:lintDebug --console=plain. git diff --check passes. Physical gesture, print-service and font/device checks are explicitly unverified.


## Batch 6 — Shared annotation surface, editor drafts and signing undo

Host preview and isolated export now share AnnotationRenderer, with identical normalized dimensions and text/stamp paint. Narrow, dual-page and tabletop editor layouts use the same interactive AnnotationPageCanvas, including page-specific text insertion and rotation. EditorSession is retained in the ViewModel across configuration recreation, guards input replacement, distinguishes cancelled save/continue/discard, and marks a saved baseline without removing the visible annotations. Editor page restore obeys the existing remember-position toggle. Signing has bounded chronological undo/redo with drag commits grouped as one operation. Pixel limits and forensic worker redaction order are preserved. Reader batch remote SHA verified: 2c0a779ceb5b191d2b0cf08930aa3df479febe81; CI run 37744566602 is tracked separately.

Closed source IDs: UX-17, UX-20, UX-22, UX-25, UX-28, UX-38.

Still open or partial: UX-03, UX-05, UX-06, UX-07, UX-08, UX-09, UX-10, UX-14, UX-21, UX-23, UX-29, UX-30, UX-31, UX-34, UX-36, UX-40, UX-41, UX-47, UX-48.

Exact files changed:

- `app-host/src/androidTest/java/com/pdfchemy/app/logic/AnnotationPixelParityTest.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/EditorSession.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/PdfEditor.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/SessionHistory.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/AnnotationPageCanvas.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PdfEditorScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/SignPdfScreen.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/AnnotationParityTest.kt`
- `pdf-ipc/src/main/java/com/pdfchemy/app/logic/AnnotationRenderer.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfEditorWorker.kt`
- `reports/ui-ux-audit-2026-10-04/UX_CURRENT_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_IMPLEMENTATION_BATCHES.md`

13 focused host tests pass (AnnotationParity, EditorSession and ReleaseWorkflowRegression), zero failures/errors. Debug assembly, debug lint and Android-test Kotlin compilation pass (BUILD SUCCESSFUL, 1m 36s); git diff --check passes. Native bitmap pixel testing was moved to AnnotationPixelParityTest in androidTest after Windows Robolectric reported missing native/windows/x86_64/robolectric-nativeruntime.dll. That test is compiled but not yet executed; pixel parity is not claimed. Command: gradlew :app-host:testDebugUnitTest --tests com.pdfchemy.app.logic.AnnotationParityTest --tests com.pdfchemy.app.logic.EditorSessionTest --tests com.pdfchemy.app.logic.ReleaseWorkflowRegressionTest :app-host:compileDebugAndroidTestKotlin :app-host:assembleDebug :app-host:lintDebug --console=plain.


## Batch 7 — File results, naming, saved folders, history and purchase readiness

Every existing single-document save launcher now uses the optional saved SAF folder policy, with editable names, explicit keep-both copy semantics, change/reset in Settings and revoked/unavailable folder fallback to the system picker. App-managed batch outputs use the same numbered no-overwrite policy. Provider display names (including staged aliases) replace opaque IDs in smart filenames. Success producers explicitly carry their own output URI; Markdown import, which creates no file, remains a state-only result. Results list actual names/types with individual Open, Share and Browse files actions, with visible viewer/share failure. History retains the existing opt-in/20-item privacy model, stores real file/MIME/directory identity, offers all entries/removal/empty guidance, and filters Merge recents to available PDFs. EPUB/CBZ inputs use a matching archive picker and bounded staging; PDF inputs retain Vanguard. Billing CTA is disabled until matching ProductDetails and Play readiness exist, with timeout/unavailable/retry, cancellation and failure messages; entitlement/encrypted cache and the existing price decision are preserved. Gray/Linearize Back routes match Compression; quick camera capture and native reading copy are distinct. Batch 6 SHA verified d269bfb97ecd9d77224ccf5e94882abb17a1213d; CI run 37745712959 is tracked separately. The Batch 6 native annotation pixel test subsequently ran successfully on a local Android 36 emulator (OK, 1 test).

Closed source IDs: UX-03, UX-06, UX-07, UX-08, UX-09, UX-31, UX-40, UX-41, UX-47.

Still open or partial: UX-05, UX-10, UX-14, UX-21, UX-23, UX-29, UX-30, UX-34, UX-36, UX-48.

Exact files changed:

- `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
- `app-host/src/main/java/com/pdfchemy/app/billing/BillingManager.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/DocumentActions.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/FileUtil.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/HistoryRepository.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/OutputPolicy.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/ShareUtil.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/AcroFormScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/AttachmentManagerScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/BookletScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/BookmarkEditorScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/BrowseOutputContract.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/CheckScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/CreateScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/DeskewScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/DocumentSanitizerScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/EbookConverterScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/FindAndReplaceScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/FlattenPdfScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/FormBuilderScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/GrayscaleOptimizerScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/HeaderFooterScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/HistoryScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ImageCompressorScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ImageReplacerScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/LinearizePdfScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/MarkdownStudioScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/MetadataSanitizerScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/NUpScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/OcrScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/OfficeExportScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/OrganizeScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PageCropperScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PageLayoutScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PageNumberScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PageOrganizerScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PdfEditorScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PreferredDocumentCreator.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/QuickFillSignScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/RedactionScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ReflowReaderScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/RepairPdfScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ScanPdfScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/SecurityScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/SignPdfScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/TableExtractorScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ToolSearch.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/WatermarkScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/textconverter/TextConverterScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/textconverter/TextConverterViewModel.kt`
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
- `app-host/src/main/res/values-pt-rBR/strings.xml`
- `app-host/src/main/res/values-pt/strings.xml`
- `app-host/src/main/res/values-ro/strings.xml`
- `app-host/src/main/res/values-ru/strings.xml`
- `app-host/src/main/res/values-th/strings.xml`
- `app-host/src/main/res/values-tr/strings.xml`
- `app-host/src/main/res/values-vi/strings.xml`
- `app-host/src/main/res/values-zh-rCN/strings.xml`
- `app-host/src/main/res/values-zh-rTW/strings.xml`
- `app-host/src/main/res/values/strings.xml`
- `app-host/src/test/java/com/pdfchemy/app/billing/BillingReadinessTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/OutputWorkflowTest.kt`
- `reports/ui-ux-audit-2026-10-04/UX_CURRENT_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_IMPLEMENTATION_BATCHES.md`

20 focused host tests pass (OutputWorkflow, BillingReadiness, ToolRegistry, FileUtil and ReleaseWorkflowRegression), zero failures/errors. Debug assembly and lint pass (zero errors, 645 warnings; BUILD SUCCESSFUL, 3m 2s). git diff --check passes after removing trailing spaces in the three migrated name-call lines. Command: gradlew :app-host:testDebugUnitTest --tests com.pdfchemy.app.logic.OutputWorkflowTest --tests com.pdfchemy.app.billing.BillingReadinessTest --tests com.pdfchemy.app.ui.ToolRegistryTest --tests com.pdfchemy.app.logic.FileUtilTest --tests com.pdfchemy.app.logic.ReleaseWorkflowRegressionTest :app-host:assembleDebug :app-host:lintDebug --console=plain. Real Play checkout/payment was not invoked.


## Batch 8 — Visual page and redaction workspaces, bounded previews, recovery and verified export parity

Split/Delete/Rotate now share visible-page selection while retaining typed ranges and selected input on failure. Organizer initializes every source identity but renders only composed lazy items, with two in-flight previews, retained 240px resolution, retry and owned bitmap disposal; bitmap previews are excluded from chronological history and undo is capped at 50 actions. Existing and added page rotations now use the original annotation plane before rotation. Raster redaction uses the exact shared painter and keeps OVERWRITE, annotation removal, quotas and fail-closed behavior. Redaction patterns now discover reviewable targets instead of bypassing preview; affected pages display fitted normalized target overlays and the reviewed URI/targets are captured before saving. Advanced compression/redaction controls retain existing choices/defaults. Global operation feedback is modal, task-aware, counts completed files and only offers cancellation for an owned job; OCR is truthfully indeterminate and follows operation state after cancellation. Provider/parse failures retain their actual diagnosis; recovery copy and optional diagnostics are separated, and dismissing errors preserves selected inputs. Large-font descriptions wrap, Home height is flexible, tool cards wrap content, and enlarged search uses a wrapping field. A concrete Batch 7 recursive save-wrapper defect is fixed and covered by an Android composition regression; unhanded fresh folder copies are cleaned up, primary output Open uses safe document actions. Batch 7 history/cancellation fixtures now verify the changed filename and fresh-copy contracts. No unrelated security implementation was changed. Previous SHA 23fd32ce23cb393eaa4626f1e7d7d2855de1dfea was verified remotely; main remains 4b3c157f3458f7df00ea94044258d33c7449ced9.

Closed source IDs: UX-10, UX-14, UX-21, UX-29, UX-30, UX-34, UX-36, UX-48.

Still open or partial: UX-05, UX-23.

Exact files changed:

- `app-host/src/androidTest/java/com/pdfchemy/app/logic/AnnotationExportParityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/logic/AnnotationPixelParityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/ui/RemainingUxRuntimeTest.kt`
- `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/OrganizerSession.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/PdfOcrEngine.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/VisualPageRanges.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/EbookConverterScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ErrorPresentation.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/OcrScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/OrganizeScreens.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PageOrganizerScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PreferredDocumentCreator.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/RedactionScreen.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/ToolSearch.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/VisualPageSelection.kt`
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
- `app-host/src/main/res/values-pt-rBR/strings.xml`
- `app-host/src/main/res/values-pt/strings.xml`
- `app-host/src/main/res/values-ro/strings.xml`
- `app-host/src/main/res/values-ru/strings.xml`
- `app-host/src/main/res/values-th/strings.xml`
- `app-host/src/main/res/values-tr/strings.xml`
- `app-host/src/main/res/values-vi/strings.xml`
- `app-host/src/main/res/values-zh-rCN/strings.xml`
- `app-host/src/main/res/values-zh-rTW/strings.xml`
- `app-host/src/main/res/values/strings.xml`
- `app-host/src/test/java/com/pdfchemy/app/logic/HistoryRepositoryTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/VisualWorkflowTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/ui/CancellationPublicationTest.kt`
- `pdf-ipc/src/main/java/com/pdfchemy/app/logic/AnnotationRenderer.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfEditorWorker.kt`
- `reports/ui-ux-audit-2026-10-04/UX_CURRENT_RECONCILIATION.md`
- `reports/ui-ux-audit-2026-10-04/UX_IMPLEMENTATION_BATCHES.md`
- `reports/ui-ux-audit-2026-10-04/UX_RUNTIME_VALIDATION_2026-10-08.md`

Full host run: 122 tests, zero failures/errors (batch8-validation-final3.log). Final affected-source focused host tests and debug build/lint pass; commands and native emulator receipts are in UX_RUNTIME_VALIDATION_2026-10-08.md. Android 36 native runtime: OK (5 tests), including eight mixed annotation/export cases. Debug assembly and Android-test assembly pass; lint has zero errors and 650 warnings. git diff --check passes. No merge, physical performance claim or audit-certification claim. All 50 original IDs have current dispositions: 48 source-DONE, two PARTIAL: UX-05 scheduled for continued implementation, UX-23 physical profiling explicitly deferred. Of the requested remaining 29, 27 source findings are closed and one remains scheduled and physical profiling is explicitly deferred.
