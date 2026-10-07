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
