# Wave 2 Audio Export — merge verification

Branch: `feature/wave-2-audio-export`. Stable base: Android `main @ 1f8e31d0aaea16d7c7efe24521e204a361b6ed5b`.

Status: implementation and full local verification complete; GitHub CI pending. Merge recommendation: **NO until CI completes**.

## Commits

- `71d2372`: conservative merge of current Android main; original feature commits `b631206` and `aae07e5` retained.
- Final implementation and verification commits: recorded after verification.

## Concrete defects and fixes

| Severity | Evidence / defect | Exact files | Applied fix | Verification |
|---|---|---|---|---|
| P1 | Writer errors could leave the drain barrier waiting indefinitely; an unbounded callback queue could consume unlimited memory. | `app-host/src/main/java/com/pdfchemy/app/logic/audio/PcmWriter.kt`, `TtsPcmAssembler.kt` | Bounded queue; synchronously enqueue barriers; flush acknowledgement; propagate writer errors; join descriptor owner before cleanup. | Unit tests cover ordering, overflow, IO failure and actual written bytes. |
| P1 | Callbacks checked state but lacked utterance/generation protection, allowing stale chunks or late cancellation callbacks. | `PcmWriter.kt`, `AudioExportAbort.kt`, `AudioExportService.kt` | Job UUID + generation UUID + chunk identity; synchronous invalidation precedes `tts.stop()` and pipeline cancellation. | Wrong identity, old chunk and cancellation tests. |
| P1 | Cancellation could hang awaiting synthesis; timeout used the wrong Android method and overwrote failure with cancellation. | `AudioExportService.kt`, `AudioExportAbort.kt` | Flow-driven cancellation; correct `onTimeout(int,int)`; terminal FAILED timeout; stop foreground/self immediately; safe IO cleanup. Notification cancellation uses the same path. | Abort ordering tests and actual service timeout regression. |
| P1 | Android 14 does not recognize `mediaProcessing`; an app targeting 36 cannot start the original manifest-selected type on that OS. | `AudioForegroundPolicy.kt`, `AudioExportService.kt`, `AndroidManifest.xml` | Declare `mediaProcessing|dataSync`, include both type permissions, select mediaProcessing on API35+ and dataSync (documented local-file processing) on API29–34; ordinary foreground API on API24–28. | SDK selection unit test; real queued-service start/cancel instrumentation; API34 added to CI. |
| P1 | Direct notification updates failed release lint because POST_NOTIFICATIONS is not declared. | `AudioExportService.kt` | Update the existing foreground notification through `startForeground()`; do not add a runtime notification prompt or suppress lint. | Release lint PASS. |
| P1 | WAV assembly did not await writer termination or enforce RIFF bounds/alignment; PCM rate/channels were unchecked. | `PcmFormat.kt`, `WavWriter.kt`, `PcmWriter.kt` | Validate rate/channels/encoding/consistent format; reject missing or partial PCM; correct RIFF/data lengths and odd-byte padding; reject overflow. Support unsigned PCM8 and signed PCM16; reject float explicitly. | Header/payload/format tests; Android MediaExtractor test. |
| P1 | Quota used CPU synthesis time and engine sink length, and counted channels twice. | `QuotaProjector.kt`, `AudioExportService.kt` | Observe PCM duration (`bytes / byteRate`), estimate remaining characters, include written bytes, free-space reserve and staging duplication, enforce classic RIFF limit. | Stereo, boundary rejection, observed-rate update and RIFF projection tests. |
| P1 | Missing offline voice silently fell back; stage dependencies were not initialized in the service; malformed UUID could crash. | `AudioExportDependencies.kt`, `AudioStagingManager.kt`, `AudioExportService.kt` | Initialize host dependencies safely; normalized bounded text in UUID cache stage; startup stale cleanup; guarded UUID; reject missing language-compatible offline voice, init and synthesis errors. | Staging/state tests; service pipeline test with controlled offline engine. |
| P1 | Publication failure left partial SAF output, and copy lacked cancellation checks. | `AudioOutputPublisher.kt`, `AudioExportService.kt`, `ui/ReflowReaderScreen.kt` | Internal validated WAV copied by host; mandatory streams; cancellation-aware chunks; best-effort partial deletion; startup/picker failures visible. Service intent carries only UUID. | Stream failure, null stream and cancellation tests; Android content-resolver publication test. |
| P1 | English Wave 2 fallbacks duplicated current main's translated Vanguard resources and broke resource compilation. | `app-host/src/main/res/values-*/strings.xml` | Remove the 15 overlapping fallback keys per locale; keep current main's `vanguard_release_strings.xml`. | Resource builds and release lint. |
| P1 | Original staging instrumentation methods used spaces in backtick names, rejected by API 24-compatible DEX; test also lacked `assertEquals` import. | `app-host/src/androidTest/java/com/pdfchemy/app/logic/audio/AudioStagingManagerTest.kt` | Add the missing import and use ordinary method names without altering assertions. | Instrumentation APK compilation PASS; connected execution awaits CI. |
| P2 | Chunking trimmed source characters and missed unspaced CJK sentence boundaries. | `AudioTextChunker.kt` | Preserve characters and surrogate pairs, prefer paragraph/sentence/word boundaries, recognize CJK sentence punctuation, reject impossible tiny limits. | ASCII, Romanian, CJK, emoji, long paragraph and engine-limit tests. |
| P2 | Broad duplicate `javax.naming` suppression and unrelated PdfJailClient import edit were inherited from feature branch. | `app-host/proguard-rules.pro`, `pdf-ipc/.../PdfJailClient.kt` | Restore exact current main versions; retain main's documented optional LDAP suppression. | Feature diff against main has no `pdf-ipc`, `pdf-jail` or ProGuard change. |

## Material files

Audio implementation and tests are under `app-host/src/{main,test,androidTest}/java/com/pdfchemy/app/logic/audio/`.

- `ReflowReaderScreen.kt`: staged text snapshot, UUID-only service startup, visible failures, progress/cancel, completed WAV open/share, reconnect to active job when re-entering Reader.
- `AndroidManifest.xml`: private mediaProcessing FGS with a dataSync compatibility declaration for older systems, required type permissions, TTS service visibility query.
- `audio_export_strings.xml`: English source strings, explicitly incomplete localization.
- `PRIVACY.md`: accurate engine-provided offline voice declaration and stage lifecycle.
- `.github/workflows/android-release.yml`: run the full security/build pipeline on this feature branch; retain API24/30/36 and add API34 for the verified foreground-type compatibility gap.

`HostOutputTransaction` exists in `pdf-ipc`; it validates isolated PDF-worker descriptors/JSON under PDF quotas. The deliberately small `AudioOutputPublisher` handles host-generated WAV instead. SAF publication is best effort, not universally atomic.

## Verification

Local result: **PASS**, 172 unit tests (35 audio tests), zero failures/errors/skips. Debug, normally signed release/R8, root release lint, securityArchitecture and instrumentation APK compilation all passed in the same invocation. App lint: 626 warnings, zero new errors; all 38 inherited baseline translation entries are now unmatched because the branch includes English fallbacks, not completed translations.

Required local command: `gradlew.bat testDebugUnitTest assembleDebug assembleRelease lintRelease securityArchitecture :app-host:assembleDebugAndroidTest`.

Executed with `--max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`, JAVA_HOME `E:\Android_Studio\jbr`. Final run: BUILD SUCCESSFUL, 19m38s. `git diff --check` and staged whitespace checks passed. `scripts/check_release_gate.py --artifact` passed: fixture hashes, 61 smoke routes, production IDs, manifest isolation and release asset hygiene. `apksigner verify` passed; certificate SHA256 remains `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b`.

Secret gate helper tests: 10 PASS. Local full-history scanner returned an incomplete result, not a pass; CI's independent secrets job remains mandatory. GitHub CI must pass architecture, dependency-security, secrets, tests, instrumentation API24/x86, API30/x86_64, API34/x86_64 and API36/x86_64, and final build. Expected instrumentation total: 197 tests per API, zero failures.

No connected Android device or local AVD was found. **Connected instrumentation not locally verified.** Compilation is not instrumentation execution.

The 11 permanent benign Vanguard fixtures and their hashes remain unchanged. The user-supplied external ten-file real-export suite and original incident provenance remain unconfirmed; synthetic fixtures are not represented as genuine producer exports or signed C2PA.

## Remaining issues and limitations

Physical smoke checklist (not executed locally): export short/long Unicode text; play the saved WAV in another app and share it; export while Reader speech is active; cancel during initialization/synthesis/publication and inspect partial outputs; background/return to Reader and cancel from notification; run Android15+ timeout with the documented test setting; retry after failure; exercise real SAF providers and an unavailable offline language.

- P0: no known Wave 2 P0 defect identified.
- P1 release gates inherited from main: Play upload-certificate registration mismatch, unidentified original compatibility corpus, and signed physical-device smoke matrix remain unresolved. This feature does not certify the whole application for release.
- P2: actual OEM/offline TTS voice installation, Android 15 timeout behavior and third-party SAF providers need signed-device smoke coverage. Controlled TTS service tests do not certify every OEM engine.
- P2: English fallback resources from `b631206` resolve build omissions but are not completed translations; new audio strings are also English-only.
- P2: Play Console foreground-service declarations must cover the mediaProcessing use and older-Android local-file dataSync compatibility path before production submission.
- WAV only; mono/stereo PCM8/PCM16 at 8–192 kHz. Float PCM and format changes fail cleanly. Classic RIFF limits apply; RF64 and compressed output are outside Phase 1.
- Jobs are not resumable across process death. Restart removes abandoned stages; provider deletion is best effort. Engines that fail to deliver PCM or timely initialization/completion fail rather than hang indefinitely.
- Android's `isNetworkConnectionRequired == false` is an engine declaration, not a cryptographic guarantee of third-party network behavior.

Platform references: [PCM callbacks](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener), [media-processing service timeout](https://developer.android.com/develop/background-work/services/fgs/timeout), [foreground service types/local file processing](https://developer.android.com/develop/background-work/services/fgs/service-types), [Android 14 type policy](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-datastore-release/android-34/android/app/ForegroundServiceTypePolicy.java), [framework unknown-type validation](https://android.googlesource.com/platform/frameworks/base/+/137ab5efb7bfd3a93b59eb484b8ad5978c63f78a/services/core/java/com/android/server/am/ActiveServices.java).
