# Signed physical-device release gate — NOT EXECUTED

Build the package with `scripts/package_release_smoke.ps1 -AndroidSdk <SDK directory>` after a signed `assembleRelease`. It contains the minified signed APK, public certificate, fixture corpus, SHA-256 receipt, this procedure, the 61-route matrix and blank results CSV. It excludes keystores, passwords and tester documents. The AAB remains a separate upload artifact pending the signing gate.

Record device model, Android/API version, build fingerprint, TTS engine/version/voice, installed application version, APK hash, tester and date. Use neutral files only. Keep evidence locally; review logs before sharing them.

1. Connect an authorized physical device; `adb devices -l` must show `device`. Install with `adb -s SERIAL install -r app-host-release.apk`. Do not uninstall or clear app data automatically. A signature mismatch with a Play installation is expected when Play's app-signing key differs; use a spare device/profile or resolve deliberately while preserving documents.
2. Launch and verify version 2.0.6 (13), settings persistence, Back/rotation/restart and recent-file opening. Check a fresh installation and an upgrade preserving existing data where signatures permit.
3. Copy `corpus/` to a user-visible device folder. Open every fixture in Reader with Vanguard enabled. Require no block, readable first/last page and functioning benign HTTPS/bookmark navigation. Record each filename separately in the corpus results CSV. Synthetic fixtures are not evidence of named-producer compatibility.
4. Execute **every route** in `tool-smoke-matrix.md` and fill its CSV: input → operation → verify content/MIME → external open/share → cancel/error → restart/source preserved. Purchases need a Play internal-test build/license tester; a sideload alone cannot certify billing. Register the device as an ad test device.
5. Execute the audio cases below. A case passes only with its expected result and recorded evidence; NOT RUN is not PASS.

| Case | Action | Required result |
|---|---|---|
| Short export | Export ordinary Reader text to local SAF, then external open/share | One playable WAV, correct duration/content, audio/wav MIME and granted read URI |
| Long / Unicode | Export many paragraphs containing Romanian, CJK and emoji around chunk boundaries | Complete audio without omitted/duplicated chunks or malformed WAV |
| Isolated Reader speech | Begin Reader speech; export; stop/start Reader speech during export | Independent TTS instances; neither operation corrupts/cancels the other |
| Picker cancellation | Cancel voice/destination dialogs | No service/partial output; navigation remains usable |
| Early cancellation | Cancel immediately during initialization | Terminal cancellation, no false success or retained ongoing notification |
| Mid-synthesis cancellation | Cancel from UI and notification separately; immediately retry | No playable partial destination; late callbacks do not write into the new job |
| Publication cancellation | Cancel during a large destination copy | Report cancellation, close descriptors and attempt partial destination deletion; provider deletion failure must not be reported as success |
| Background / lock | Export, switch apps and lock device | Progress survives; notification is cancellable; completion stops service |
| API 34 foreground type | Export on Android 14 | No InvalidForegroundServiceTypeException; dataSync compatibility path starts |
| API 35+ foreground type | Export on Android 15+ | mediaProcessing starts; timeout is terminal failure with stopped service and cleaned stage |
| Missing / unsupported voice | Remove offline voice or select unavailable language | Clear failure; no corrupt WAV or stranded service |
| Storage pressure | Export with nearly full storage | Controlled quota failure, no successful corrupt output |
| SAF providers | Repeat local DocumentsUI and available cloud provider; reject/revoke destination access | Correct success or explicit failure; partial destination cleanup is best effort, never claimed universally atomic |
| Concurrent export | Attempt a second job while one runs | Rejected/controlled, first job remains consistent |
| Process interruption | Force-stop only the test installation during export, relaunch and retry | No false completed job; stale internal stage is cleaned according to policy |

For Android 15 timeout testing, use a dedicated test device and Android's documented FGS timeout test controls, record previous settings and restore them afterward. Do not change global timeout settings on the user's daily device without a deliberate test decision. Deterministic automated timeout tests complement but do not replace this physical path.

Capture app logs while it is running: `adb -s SERIAL shell pidof -s com.pdfchemy.app`, then `adb -s SERIAL logcat --pid=PID -v threadtime > app-log.txt`. This capture ends when the process changes; capture again after restart. Obtain crash-buffer evidence after a crash and review it for unrelated/private content. Do not clear log buffers or app data to obtain a passing result.

P1 gate closes only after the signed physical matrix has actual PASS evidence, real producer coverage is accepted, and upload certificate registration matches. Current physical status: **NOT RUN — no connected device available locally**.
