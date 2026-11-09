**SCAN-01: preserve published split and image-export outputs**

Baseline: `baa28b205171303ee14cfe714025e4d6b9fbb957` in `kiss2oblivion/pdfchemy-android`.
Implementation branch: `fix/2.1.0-split-publication-retention`.
Scope: SCAN-01 only; the audio, speech, native-dependency and later plan batches are separate.

**Behavior**

`PdfManipulator.splitPdf`, `splitByBlankPages`, `splitByBookmarks` and `convertPdfToImages` now clean up through `OutputPolicy.deleteIfFresh`. Their former direct `DocumentFile.delete()` calls ignored publication state, active writers and another owner's claim. Duplicate nested cleanup is removed from the blank/bookmark split and image-export paths.

The shared registry now decides whether each URI is eligible for wrapper cleanup. Published or claimed outputs survive. An owned incomplete copy is removed once when safe; deletion refusal/error retains its fresh provenance for retry. Original errors and cancellation still propagate.

This does not introduce per-file atomic commits for a multi-output gateway transaction. Outputs from a transaction that has not reached its publication boundary remain incomplete under the existing policy.

**Regression evidence**

Before production edits, all 11 new `SplitPublicationIntegrityTest` methods failed against the baseline. The four actual coroutine-cancellation cases each deleted a published output. Other cases exposed deletion during an active writer, deletion of another owner's claim, ignored mixed publication states and repeated cleanup after failure/refusal. The successful baseline reproduction is preserved at `.scan01-evidence/baseline-regressions.xml` and `scan01-baseline-unit-final.log`.

The new class exercises all four public wrappers. It includes four individual cancellation tests and seven further scenarios across every wrapper: errors after publication, unpublished-copy cleanup, mixed publication states, refused deletion/retry, an active writer, a foreign ownership claim and cleanup exceptions preserving the original failure. The passing class covers 32 wrapper/scenario combinations.

The existing provider-creation-failure regression retains its deletion assertions. Its fake provider now resolves the created URI to the same mocked document, matching the ownership-aware cleanup API.

Focused verification: all 11 new methods plus all eight `ReleaseWorkflowRegressionTest` methods pass. Provider/gateway boundaries are mocked in these JVM tests; they do not replace native SAF verification.

**Native coverage**

`SplitPublicationDeviceTest` uses the existing separate-UID SAF provider and real isolated worker. Its four success cases cover ordinary split, blank-page split, bookmark split and PDF-to-image export. It checks distinct readable outputs, retired freshness, protection from stale cleanup, unchanged source bytes and unchanged bytes in an existing provider document.

Native results: 27 complete passing methods on each API 24 x86 and API 36 x86_64. The raw runner output was checked with `scripts/run_android_instrumentation.py::validate_result`, requiring a successful terminal summary and a passing result for every declared method. The scope includes `SplitPublicationDeviceTest` (4), `PdfManipulatorTest` (4), `SafCleanupOwnershipTest` (2), `SafFreshCopyPublicationTest` (4), `SafExistingDestinationIntegrityTest` (4), `TextOutputPublicationIntegrityTest` (7), and `PublishedBatchCancellationSecurityTest` (2).

Both devices installed identical artifacts:

| Artifact | SHA-256 |
|---|---|
| Debug app APK | `3735b702008c6eeee6100a37868e75428595521d88f8428c78076a5429c37fce` |
| Instrumentation APK | `176005d90627f73f173a18d1525a4380197d0cc05bd13c4362a13f26944c55d9` |

**Verification**

| Local check | Result |
|---|---|
| Host units | 533 tests, zero failures/errors/skips |
| IPC units | 115 tests, zero failures/errors/skips |
| Debug app and instrumentation APK assembly | Pass |
| Release lint | Zero errors; 622 warnings |
| Security audit and architecture checks | Pass |
| Full-history TruffleHog 3.97.9 | Six exact reviewed historical matches; zero rejected findings |
| `git diff --check` | Pass |

The complete local Gradle command passed in 7m 53s with JDK 21:

```powershell
.\gradlew.bat :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :app-host:assembleDebug :app-host:assembleDebugAndroidTest :app-host:lintRelease :app-host:securityAudit securityArchitecture --offline --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

The scoped native helper installs the two APKs with `adb install --no-streaming -r` and invokes `am instrument -w -r -e class ...`. Local evidence is retained in `scan01-focused-unit.log`, `scan01-full-verification.log`, `scan01-native-emulator-5580.log`, `scan01-native-emulator-5582.log`, `scan01-secrets.log`, and `.scan01-evidence/` (baseline/focused XML, raw install/instrumentation logs and the native helper).

This receipt records local verification available before the implementation commit. Same-source CI is tracked by the workflow after push; prior baseline CI is not verification of this change.
Physical/signed-release verification: not performed for this batch.

The first sandboxed baseline attempt could not delete compiler output through a new build junction on D: and ran no tests. Generated `app-host/build` files were moved from C: (only approximately 420 MiB free) to `D:\AndroidEmulator\pdfchemy-fix01\builds\app-host`, retaining their original path through a junction. Subsequent Gradle runs used write access to that generated directory.

The first executable test run demonstrated the four cancellation failures but also exposed an incorrect identity assumption in the new test helper: coroutine stack-trace recovery can copy an exception. The helper was corrected to verify its type, message and original causal chain. The subsequent unchanged-production run produced all 11 substantive failures before the fix was applied. Both attempts are retained.

Only explicit implementation/test/receipt paths belong in the commit. Machine-specific build configuration, emulator files, local logs and `.scan01-evidence` remain outside the source change. Original checkouts, their untracked work and the saved FIX-02 prototype remain preserved.
