# Android fixes implementation receipt

Repository: `kiss2oblivion/pdfchemy-android`.
Baseline: `454ab9c033ec867c6ba5995782b92e1fb3333669` (remote main verified before implementation).
Branch: `fix/android-selection-publication-attachments`.
This receipt accompanies the implementation commit. The unfinished reconciliation checkout was preserved.

## Implemented

1. **Batch input lifetime:** atomic staging leases, explicit picker ownership, synchronous retention by merge/compression selections, release on removal/disposal/reset, and separate leases for queued operations. Failure, cancellation and encrypted-document outcomes clean up or transfer their ownership.
2. **Audio publication cleanup:** freshness retires immediately after successful stream closure, before a suspension boundary. Cleanup uses the registry's atomic ownership claim, preserves published/unknown destinations and active writers, and retains cleanup ownership when deletion fails. Failed-copy cleanup runs after the writer handle is released.
3. **Attachment identity:** the exact embedded-file name-tree key travels as `attachmentId` through worker responses, validated IPC contracts, host models, UI row keys and extraction requests. Extraction/removal use the identifier without filename fallback. Duplicate response identifiers are rejected. Optional unknown attachment sizes are normalized to the existing zero placeholder.

Attachment identity uses the currently supported flat embedded-file name tree. Nested-tree support was not added.

## Verification

| Check | Result |
|---|---|
| Host unit suite | 532 passed, zero failures/skips |
| IPC unit suite | 117 passed, zero failures/skips |
| Worker unit suite | 143 passed, zero failures/skips |
| Debug app APK and instrumentation APK | Built successfully |
| API 24 device regressions | 4/4 passed |
| API 36 device regressions | 4/4 passed |
| Device crash buffers | No crash entries from either final run |
| `git diff --check` | Passed |
| Lint | Existing library error; see below |

Device regressions cover five-document merging with Vanguard off/on and eviction pressure, attachment extraction/removal across the isolated-worker protocol, and cancellation-driven cleanup after audio publication through a SAF fixture. These exercise processor/worker/provider behavior, rather than automated picker UI interaction.

The new unit regressions reproduced the original staging eviction and audio cleanup failures. Attachment lookup regressions reproduced wrong-payload extraction and missing identity. The first device run exposed an omitted `/Params/Size` reporting `-1`; that case was corrected, added to the unit suite, and both APIs passed after rebuilding.

## Lint limitation

The combined lint run reported `NewApi` at `pdf-ipc/src/main/java/com/pdfchemy/app/jail/BoundedWorkerStorage.kt:75`: `Os.fcntlInt` requires API 30 according to lint while min SDK is 24. That file is byte-identical to the baseline. No suppression or unrelated storage change was added. Remaining combined lint work was stopped after this failure; a clean lint gate is not claimed.

## Evidence and reproduction

- `.verification/final-checks-retry.log`: successful final unit/build run.
- `.verification/api24-final-instrumentation.log`, `.verification/api36-final-instrumentation.log`: final device results.
- `.verification/api24-final-crashes.log`, `.verification/api36-final-crashes.log`: final crash buffers.
- `.verification/picker-baseline.log`, `.verification/audio-baseline.log`, `.verification/attachment-baseline-lookup.log`: original regression failures.
- `.verification/full-checks.log`: combined run and existing lint finding.
- `.verification/manifest.json`: changed-source and APK SHA-256 hashes plus suite counts.
- `.verification/changes.patch`: tracked changes and new source/test files, without build artifacts.

Final Gradle tasks:

```text
:app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest
:app-host:assembleDebug :app-host:assembleDebugAndroidTest
```

Run using JDK 21, the existing D: Gradle cache, D: temporary storage, `--offline --no-daemon --max-workers=2`, quoted property `'-Pkotlin.compiler.execution.strategy=in-process'`, and `-I .verification/storage.gradle`.

Device test class: `com.pdfchemy.app.security.AndroidFixesDeviceTest`.
The temporary emulator instances used read-only AVD sessions with snapshots disabled and were shut down after verification.
