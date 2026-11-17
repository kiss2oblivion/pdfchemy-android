# FIX-01: compression publication

Baseline: `454ab9c033ec867c6ba5995782b92e1fb3333669` on `kiss2oblivion/pdfchemy-android`.
Implementation branch: `fix/2.1.0-compressor-publication`.
Disposition: the first implementation passed its local scope but failed complete native CI. The cancellation repair below passes fresh local verification; complete CI remains open. Release-device evidence remains a separate gate. FIX-02 through FIX-04 are not included.

## Behavior

The public compressor now rejects existing/unknown SAF destinations before staging or destructive provider access. It claims a proven fresh destination before processing and atomically transfers that claim to its final `HostOutputTransaction`. The final collector starts after compression passes finish, preserving its full storage deadline.

The selected compression result passes through bounded host storage and immutable snapshots before publication. Provider capabilities remain in the host. Compression passes propagate cancellation, and final publication participates in shared writer coordination.

ViewModel cancellation, error and destruction cleanup consult current fresh-output ownership instead of deleting from a stale tracked record. A transaction that has published and retired freshness cannot have its completed output deleted while the result is returning to the UI. Batch failure/cancellation follows the same rule.

Pipe publication uses nonblocking descriptor writes and 50 ms polling intervals with cancellation checks. This is necessary on API 24, where closing a stream from another thread did not promptly release the test's blocked pipe write. Regular output streams retain their existing behavior and cancellation-driven stream closure. Uninterruptible provider opens remain protected until the writer stops.

## Regression evidence

Before production edits, all three new ViewModel result-delivery regressions failed because cleanup called `DocumentFile.delete()` after publication. They now pass for cancellation, error and destruction.

An intermediate implementation passed 23 native methods on API 36 but failed one of 23 on API 24: `cancellationClosesBlockedFreshPublicationBeforeCleanup`. The failed log is retained locally. The test's five-second writer-stop assertion was preserved; the implementation was corrected with nonblocking pipe writes. Final API 24 and API 36 runs both pass all 23 methods.

New native compressor coverage exercises the real public API and isolated worker:

- Existing destination configured to truncate and throw: zero opens, unchanged destination/source bytes.
- Large successful output: valid PDF, preserved source, retired freshness, and rejected reuse without a second open.
- Fresh output failures at open and partial write: owned incomplete output cleanup.
- Source-read failure: cleanup of the never-opened fresh destination.
- Provider stops reading: cancellation terminates the real writer before fresh-output cleanup.

Two IPC unit tests cover exclusive ownership transfer and preservation of every original claim when a batch transfer is rejected. Existing batch cleanup sentinels now register real freshness rather than supplying only a Boolean claim.

## Initial implementation local verification

| Check | Result |
|---|---|
| Host unit suite | 522 tests, zero failures/errors/skips |
| IPC unit suite | 115 tests, zero failures/errors/skips |
| API 24 x86 scoped native suite | 23 methods pass; complete per-method statuses and successful terminal result |
| API 36 x86_64 scoped native suite | Same 23 methods pass; complete per-method statuses and successful terminal result |
| Debug app and instrumentation APK assembly | Pass |
| Release lint | Zero errors; 624 warnings |
| `:app-host:securityAudit` and `securityArchitecture` | Pass |
| `git diff --check` | Pass |

The native suite includes `CompressorPublicationIntegrityTest` (5), `SafExistingDestinationIntegrityTest` (4), `SafFreshCopyPublicationTest` (4), `PublishedBatchCancellationSecurityTest` (2), `WarningPublicationSecurityTest` (5), and three synthetic-document `PdfCompressorTest` methods: `testCompressionReducesFileSize`, `testCompressPdfDeduplicatesSharedImagesOnAndroid`, and `testAdvancedCompressionOptions`.

Both final native runs installed identical artifacts:

| Artifact | SHA-256 |
|---|---|
| Debug app APK | `b9bcd69a071ce796d322bfdc45c3dc351002696ba03a3b94dc9d34e8c342a427` |
| Instrumentation APK | `72f1015497efb3e37ab4c5572bf578ab84550775c740fe68d8a4dec759c5c8c0` |

Final Gradle command, with JDK 21 configured:

```powershell
.\gradlew.bat :pdf-ipc:testDebugUnitTest :app-host:testDebugUnitTest :app-host:assembleDebug :app-host:assembleDebugAndroidTest :app-host:lintRelease :app-host:securityAudit securityArchitecture --offline --max-workers=2 "-Pkotlin.compiler.execution.strategy=in-process"
git diff --check
```

The local native harness uses `adb install --no-streaming`, then `am instrument -w -r -e class ...`. It validates the full raw runner output with `scripts/run_android_instrumentation.py::validate_result`, requiring every declared method to complete successfully; an ADB exit code alone is insufficient.

Local evidence is retained in the isolated implementation worktree: `fix01-baseline-unit.log`, `.fix01-evidence/baseline-cancellation-publication.xml`, `.fix01-evidence/emulator-5582/initial-blocked-write-failure.log`, `fix01-final-verification.log`, `fix01-native-5580-final.log`, `fix01-native-5582-final.log`, and the raw instrumentation logs under `.fix01-evidence/`. Local runtime logs and machine configuration are not part of the application change.

No signed-release or physical-device run was performed for this fix. Existing checkout work was preserved in place; implementation and verification used a separate worktree and newly created test emulators.

## Complete CI failure and cancellation repair

[CI run 42408757583](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/42408757583) tested implementation commit `7c3bc83c22a09fb1deb1d9e19da18286e14d7db2`. Unit, architecture, dependency-security and secrets jobs passed. Native jobs on API 24, 30, 34 and 36 each failed the same existing method: `TextOutputPublicationIntegrityTest.cancellationDuringOpenCleansOnlyAfterWriterReturns`. Each native job reported one failed method; the build job was skipped.

When a blocked provider open returned after cancellation, registration closed the returned stream correctly. The new pipe probe then called `fstat` on that closed descriptor and raised `EBADF`, replacing cancellation with a failure. The initial local scope omitted this existing text-publication class.

The repair checks cancellation before constructing the provider writer and checks it again when any exception escapes the publication I/O block. This preserves cancellation if a concurrent close races with probing, writing or closing, while preserving the original I/O exception for an active coroutine. The entire seven-method `TextOutputPublicationIntegrityTest` class is added to the local native scope, including successful text publication and genuine provider failures.

Repair verification:

- 115 IPC and 522 host unit tests pass, zero failures/errors/skips.
- API 24 and API 36 each pass 30 native methods: the original 23 plus all seven text-publication methods. Every declared method completed with a passing status; both runs have a valid terminal summary.
- Debug app/test assembly, release lint (zero errors; 624 warnings), security audit, architecture checks and `git diff --check` pass.
- The sandboxed full-history secret scan was incomplete. Its unrestricted retry completed with TruffleHog 3.97.9: six exact reviewed historical matches and zero rejected findings.
- Gradle command: identical to the initial verification command above; result `BUILD SUCCESSFUL in 6m 26s`.
- Raw evidence: `fix01-repair-verification.log`, `fix01-repair-native-emulator-5580.log`, `fix01-repair-native-emulator-5582.log`, and `.fix01-evidence/emulator-5580-fix01-repair/` and `emulator-5582-fix01-repair/`.

Both native repair runs installed identical artifacts:

| Artifact | SHA-256 |
|---|---|
| Debug app APK | `cf568584e50ee25d0f4fb5b214fa1af8ea3c24c63de73800b3c67be33809f280` |
| Instrumentation APK | `bf74898a84fae7ce130f1f693577415a733bfb7ead34301382bfaedbdb0ac7e7` |
