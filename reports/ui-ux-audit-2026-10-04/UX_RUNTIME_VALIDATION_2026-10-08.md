# UX implementation runtime validation — 8 October 2026

Scope: Codex UX implementation branch, not release certification or a security
audit. Tests exercise the existing isolated renderer/export gateway; the host
does not gain a PDF parser or a new IPC authority.

## Automated evidence

- Full `:app-host:testDebugUnitTest`: **122 tests, zero failures/errors**.
  The final full run also assembled debug and Android-test APKs and ran lint.
- Subsequent source checks: affected `VisualWorkflowTest`, `LatestRequestTest`,
  `ToolRegistryTest`, `CancellationPublicationTest`; debug assembly and lint.
- Lint: **zero errors, 650 warnings**. Warnings are retained rather than hidden.
- `git diff --check`: passes.
- Android 36 Play-image x86_64 emulator, WHPX/SwiftShader, native Android pixels:
  **OK (5 tests)**; the verified run took 55.58 seconds. That is test-suite
  duration, not a Reader performance measurement.

Native test coverage:

1. Preferred output wrapper composes without recursion. This catches the Batch 7
   accidental self-call that unit build/assembly did not detect.
2. Category and tool labels at 320dp/font2.0 remain reachable by scrolling,
   have no ellipsized lines and keep glyph bounds inside their measured width.
   A raw Compose `hasVisualOverflow` flag also counts unused paragraph width;
   glyph bounds and ellipsis checks test the actual clipping requirement.
3. Enlarged search accepts a real query and selects the native Reader route.
4. Redaction coordinates match preview/export widths, with an explicit empty
   label so the test samples opaque blocks rather than white label glyphs.
5. Actual worker-exported PDFs match preview across **eight cases**: existing
   rotation 0/90/180/270, an additional 90-degree rotation, mixed drawing/text/
   stamp annotations, with and without labeled redaction. Assertions check
   colored annotation centroids, aspect ratios and opaque redaction corners.

The first full run exposed three old history-title expectations and an incomplete
writable-folder test fixture. Those now verify actual filenames/MIME and the
fresh-copy cleanup contract. Enlarged descriptions initially clipped and were
fixed. A sample inside white redaction lettering was replaced by opaque-corner
assertions. Failed attempts are not counted as passes.

Reproduction commands (Windows, JAVA_HOME set to the Android Studio JBR):

```powershell
.\gradlew.bat :app-host:testDebugUnitTest :app-host:assembleDebug :app-host:assembleDebugAndroidTest :app-host:lintDebug --console=plain
.\gradlew.bat :app-host:assembleDebug :app-host:lintDebug :app-host:testDebugUnitTest --tests '*VisualWorkflowTest' --tests '*LatestRequestTest' --tests '*ToolRegistryTest' --tests '*CancellationPublicationTest' --console=plain
adb -s emulator-5580 install -r app-host/build/outputs/apk/debug/app-host-debug.apk
adb -s emulator-5580 install -r app-host/build/outputs/apk/androidTest/debug/app-host-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w -r -e class com.pdfchemy.app.ui.RemainingUxRuntimeTest,com.pdfchemy.app.logic.AnnotationPixelParityTest,com.pdfchemy.app.logic.AnnotationExportParityTest com.pdfchemy.app.test/androidx.test.runner.AndroidJUnitRunner
```

The emulator's logical width was also set to 320dp (960px, density480) and system
font scale2.0. Compose tests explicitly constrain width and provide font2.0.
These checks do not replace physical TalkBack/foldable/printer/provider/Play
validation. Real purchases and consented ad loading were not invoked.

## Remaining scoped work and explicit hardware deferral

**UX-05, completed in Batch 9:** real worker page counts, a distinct saving
phase, operation-bound validated telemetry and cancellation guards are implemented.
Actual searchable output, precise page-event ordering, cancellation without target
overwrite or late progress, and existing worker isolation checks pass. See
VANGUARD_OCR_VERIFICATION_2026-10-08.md for exact scope and test receipts.

**UX-23, physical performance evidence:** no physical Android device is attached;
only a debug emulator is available. Main-thread decoding/painting source defects
and bounded previews are addressed. Reader navigation, annotation interaction,
100–500-page thumbnail memory/latency and safeguard calibration (O-01/O-04) need
release-like traces on representative hardware. Resume with that hardware;
emulator test timing does not justify changing memory safeguards or certifying
physical responsiveness.

UX-05 implementation is complete; UX-23 requires physical hardware.
The current matrix has all 50 original IDs. The branch remains unmerged and
the UI/UX audit has **not** been declared fully validated or certified.
