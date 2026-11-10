# FIX-02: require installed offline voices for Reader and WAV export

Baseline: `d59d00be945b8d72e388f6a79771dd3713733632` (audio destination admission). The saved speech prototype remains preserved in stash `c1355be0d9da189649ff06955e9df6e31766574b`; selected new files were recovered without replacing the current Reader or audio implementation.

## Behavior

`OfflineSpeechPolicy` chooses an installed voice declaring no network requirement, excludes `KEY_FEATURE_NOT_INSTALLED`, prefers an exact locale and then a deterministic same-language fallback, and verifies the voice reported after successful selection. Reader playback and WAV synthesis share this policy. Each Reader paragraph and WAV chunk checks the selected voice again before submitting document text. No subsequent language setter replaces the selected voice.

Reader speech now has initializing, ready, unavailable and closed states. A play request can wait for initialization, with a 30-second initialization deadline. Pause, document replacement and disposal retire pending requests and utterance IDs; stale callbacks cannot advance playback. Lifecycle stop also pauses speech. Retry creates a new session and requires an explicit play request. Unavailable speech offers Settings and Retry, with a message translated for all 21 resource locales. WAV failures caused by offline unavailability display the same localized message and a Settings action.

WAV export keeps the destination ownership and completed-output retention introduced by SCAN-02/03. Its initialization failure/timeout and missing, uninstalled or rejected voice produce an offline-unavailable failure before text submission. Publication, staging and claim cleanup remain covered by earlier regressions.

These checks enforce the engine's voice declarations and selection results. They do not independently certify the internal behavior of a third-party TTS engine.

## Regression evidence

The original native fixture could not be discovered reliably because its service intent filter omitted `android.intent.category.DEFAULT`. Android's engine lookup uses `MATCH_DEFAULT_ONLY`; see the [AOSP engine lookup implementation](https://android.googlesource.com/platform/frameworks/base/+/d24f94989fe5e78b82af261727d3c2ba11639772/core/java/android/speech/tts/TtsEngines.java). The repaired fixture runs in the separate installed test APK/process. Native Reader tests require the fixture engine to be discoverable, require its exact voice-name set, and require a recorded voice query before accepting behavior as fixture evidence.

With that fixture and unchanged speech behavior (apart from the constructor test seam), both baseline Reader tests fail: a network-only engine receives one document paragraph, and a mixed engine retains its network default. Raw baseline evidence is `.fix02-evidence/emulator-5580-baseline/instrumentation.log` and `fix02-reader-baseline-native.log`.

The final 19-method policy/session suite covers installed/locale selection, rejected/missing/changed voices, pending initialization, errors/deadline, rate failure, continuation identities, pause, replacement documents with identical text and disposal. The first integrated JVM run exposed Robolectric 4.11.1's missing `getVoice()` implementation: its shadow tracks the selected voice separately. The PCM service shadow now reports that tracked voice; the production readiness check remains enforced. First-run XML is preserved as `.fix02-evidence/first-focused-service.xml`.

Three Reader native tests verify network-only rejection, offline override of a network default, and Settings/Retry recovery with no automatic text submission. Three WAV service native tests run the actual foreground service against the installed fixture and verify network-only and uninstalled-voice rejection (zero text submissions and zero destination opens), plus a valid 3,244-byte WAV published with the offline voice. Their temporary default-engine setting is restored in `finally`, including on assertion/cleanup failures; both dedicated devices returned to their prior unset setting.

## Local verification

| Check | Result |
|---|---|
| Focused policy/audio units | 66 passed |
| Complete host units | 577 passed, zero failures/errors/skips |
| IPC units | 115 passed, zero failures/errors/skips |
| API 24 native selection | 38 methods passed, strict complete-run validation |
| API 36 native selection | 38 methods passed, strict complete-run validation |
| Debug app / instrumentation APK builds | Passed |
| Release lint | Zero errors, 622 warnings |
| Host security audit and security architecture | Passed |
| Secret check | Scan the implementation commit before push; record exact result in the execution ledger |
| Debug app APK SHA-256 | `3c0130fb57071da0088b89a6365624461f488e90ef5e7a4fab552ebd73153832` |
| Instrumentation APK SHA-256 | `996bc3fb084549201932e3f541dff31c9fba53d8b8766c62de370632b1e1c82c` |

Full verification passed in 10m 40s (JDK 21, cached offline dependencies):

```powershell
.\gradlew.bat :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :app-host:assembleDebug :app-host:assembleDebugAndroidTest :app-host:lintRelease :app-host:securityAudit securityArchitecture --offline --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

Native selection also includes the prior audio admission, completed-output retention, recovered-output UI, WAV platform and SAF/text/batch ownership regressions. Logs and focused XML remain under `.fix02-evidence/` with task logs named `fix02-*.log`. Generated build output uses D:. The two dedicated emulators were stopped after successful device checks. Same-source CI follows commit/push; physical/signed-release acceptance remains separate. No original checkout work or saved prototype was overwritten.

## Follow-up: API 34 test dispatcher (2026-11-10)

Implementation commit `fbe7c6cffe7b599cb36347dabe7bf271fb6f30b7` passed the committed-HEAD secret check (six exact reviewed historical matches, zero rejected) and was pushed to the Android remote. Initial [CI run 42690116808](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/42690116808) completed 532 native methods on each API 24/30/34/36; API 24/30/36 passed, while API 34 reported one failure in `retryRechecksInstalledVoicesAndWaitsForAnExplicitPlay`. The final build job was skipped. This run is not a CI pass.

The exception was `IllegalStateException: The current thread must have a looper!` in `LazyLayoutPrefetcher`, with a Compose test frame resumed through an IO dispatcher. The new fixture omitted the explicit `StandardTestDispatcher` already used by `ReaderDocumentLifecycleTest` and `VanguardReaderNavigationTest`. Its rule now queues recomposition on the UI test clock instead of applying layout inline on an IO producer thread. Speech assertions, fixture-binding checks and timeouts are unchanged; production source is unchanged from `fbe7c6cf`. The dispatcher behavior is documented in the [Android Compose test context reference](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/AndroidComposeUiTestEnvironment).

The instrumentation APK build passes after the repair (1m 12s). All three speech methods pass locally on a dedicated API 34 Google APIs emulator, including the exact retry regression. The strict runner validates all three completions. The test APK SHA-256 is `e0e419dedd98d4d8fcebf6c0477bf7459c606a83c4aaa582ccbbc13bcca09e18`; the application APK is unchanged from the implementation hash above.

Two broader 13-method local selections remain incomplete after the Windows emulator exited during the unchanged `ReaderDocumentLifecycleTest.survivingViewModelRetainsReaderSnapshotAcrossCompositionRecreation` case, after two completed methods. The monitored second attempt, with Vulkan disabled, recorded emulator exit `-1073741819` (`0xC0000005`, host access violation). Neither broader attempt is counted as a pass. The targeted three-method speech selection completed successfully between those attempts. This host exit is distinct from the earlier CI test-frame Looper exception; the full matrix must be verified again in CI.

CI failure metadata is `.fix02-evidence/ci-api34-failure.json`; host-exit metadata is `.fix02-evidence/api34-host-exit.json`; raw local logs are `fix02-ci-repair-*.log`. Only the fixture clock and this receipt change in the follow-up. The committed-HEAD secret scan is a pre-push gate, and full same-source CI remains required for closure.
