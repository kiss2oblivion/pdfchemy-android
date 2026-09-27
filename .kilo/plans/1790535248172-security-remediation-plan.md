# Security Remediation Plan — PDFchemy Android

Derived from the Deep Dive Audit (C1–C4, H1–H7, M1–M11, L1–L12). Sequence differs from the
draft in two places: a **Phase 0** is inserted before all other work, and the **redaction
toggle deletion is moved after the IPC isolation fix**. Rationale in "Key decisions".

Target: `app/src/main` (147 Kotlin files), `app/src/test` (44 files), `app/build.gradle.kts`,
`gradle.properties`, `AndroidManifest.xml`, root docs, repo hygiene.

---

## Key decisions

1. **Phase 0 is a hard gate.** The unit test source set does not currently compile, and
   `signingConfigs` throws at configuration time without secrets. Neither the XFA sanitizer
   rewrite nor the redaction work can be validated until both are fixed. No Phase 1–5 change
   should land before Phase 0 is green.

2. **Redaction is the one open architectural risk (see Open Question 1).** Deleting the
   `forensicSanitize` toggle makes the forensic raster path mandatory, and that path depends
   on a bind that likely fails. The plan therefore splits C3 into two tasks: resolve the IPC
   bind (Phase 2.3) *first*, then delete the toggle (Phase 2.4). **Do not merge these into the
   same commit** — if the bind turns out to work, the toggle deletion is a 1-file change; if it
   doesn't, you need to fall back to Open Question 1's alternative.

3. **"Protect PDF" gets a UI, not just a hardcoded policy.** Setting restrictive
   `AccessPermission` bits by default (C4) silently changes behavior for existing users who
   expect to print their own protected PDF. Requires a decision (Open Question 2).

---

## Phase 0 — Build and test infrastructure (gate)

*Nothing else proceeds until this is complete.*

| # | Task | Files |
|---|---|---|
| 0.1 | Make `signingConfigs` lazy so a missing `keystore.properties` no longer breaks every task. Wrap the `create("release") { }` body in `if (project.hasProperty("..."))`-style guard, or move the `throw GradleException` into a `release` buildType branch that only resolves when assembling release. | `app/build.gradle.kts:33-46` |
| 0.2 | Remove the four properties AGP 9 rejects: `android.defaults.buildfeatures.resvalues`, `android.sdk.defaultTargetSdkToCompileSdkIfUnset`, `android.enableAppCompileTimeRClass`, `android.r8.strictFullModeForKeepRules`. Reassess `android.newDsl=false` + `android.builtInKotlin=false` (documented AGP 9 failure mode) — remove both if the Kotlin plugin applies cleanly. | `gradle.properties:4,5,6,10,12,13` |
| 0.3 | Raise `org.gradle.jvmargs` to `-Xmx4g` — `ResourceLeakStressTest` allocates ~50 × 16 MB bitmaps and Robolectric forks its own JVM. | `gradle.properties:1` |
| 0.4 | **Fix the shadowed-parameter bug.** `var inputStream: InputStream? = null` at `:60` and `:150` shadows the non-null `inputStream: InputStream` parameter from `:55` and `:144`; `PDDocument.load` at `:69` and `:159` therefore always receives `null`. Delete both shadowing locals so the parameters are used. | `logic/PdfRedactionEngine.kt:60,150` |
| 0.5 | **Fix the compression fail-always bug.** `targetFd` is closed by the `use` block ending at `:74`; `:78` then calls `targetFd.statSize` → `IllegalStateException` → `onFailure`. Use `streamOut`'s byte count, or query size before the `use` closes, or have the host stat the destination. | `jail/PdfJailService.kt:78` |
| 0.6 | Fix the 4 test files calling `PdfRedactionEngine` with `Uri` where the signature takes `InputStream`; they must construct streams. Same for `PdfToEpubEngine.pdfToEpub` in `PdfToEpubEngineTest.kt:67`. | `PdfRedactionEngineTest.kt:53,72`; `StressGauntletAndroidTest.kt:67,74`; `AcroFormAndRedactionAuditTest.kt:136,148,159`; `PdfToEpubEngineTest.kt:67` |
| 0.7 | Delete the two CI no-op tests that hardcode `C:\Users\cucos\...` paths and assert nothing. | `PdfCompressorTest.kt:123-207, 245-324` |
| 0.8 | Re-enable lint as a gate (`abortOnError = true`, `checkReleaseBuilds = true`) after a baseline, so Phase 1–5 don't add new violations silently. | `app/build.gradle.kts:28-31` |
| 0.9 | Add CI: `.github/workflows/android.yml` running `assembleDebug` and `testDebugUnitTest` on push/PR. No signing secrets required. | new file |

**Exit criteria:** `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` both
succeed on a clean checkout with no local secrets. Redaction and compression have at least one
passing behavioural test each.

---

## Phase 1 — Truth, consent, and access control

Fixes C1, C4, M3, H6, and the H7 documentation/manifest half. All low-ambiguity, no
dependencies on Phase 2.

| # | Task | Files |
|---|---|---|
| 1.1 | **Close the premium + consent bypass (C1).** Two parts, both required: (a) gate the intent extra — `val isScreenshotRun = BuildConfig.DEBUG && intent.getBooleanExtra("isScreenshotRun", false)`; (b) remove the compiled-in `IS_PREMIUM` debug field, which is a second, source-independent bypass. | `MainActivity.kt:380,703,1139`; `app/build.gradle.kts:54,67` |
| 1.2 | **Secure the clipboard (M3).** Set `EXTRA_IS_SENSITIVE` on the error-trace clip so the platform marks it sensitive and the paste toast behaves. | `MainActivity.kt:1102-1104` |
| 1.3 | **Apply `FLAG_SECURE` (H6).** Apply per-document-rendering window rather than globally, so settings/marketing screens still appear in Play listing screenshots. Add a `preview_mode` escape used only by `androidTest`. Affects ~10 render sites. | `ui/PdfEditorScreen.kt`, `SignPdfScreen.kt`, `RedactionScreen.kt`, `ReflowReaderScreen.kt`, `PageCropperScreen.kt`, `WatermarkScreen.kt`, `DeskewScreen.kt`, `GrayscaleOptimizerScreen.kt`, `QuickFillSignScreen.kt`, `FormBuilderScreen.kt` |
| 1.4 | **Make "Protect PDF" actually restrict (C4)** — see Open Question 2 for the default policy. | `logic/PdfManipulator.kt:326-341` |
| 1.5 | **Delete the destructive API (M10).** Remove `clearMetadataOverwrite` outright. It has no callers; the in-place `"wt"` truncate has no safe future use. | `logic/PdfMetadataManager.kt:139-190` |
| 1.6 | **Validate incoming intent URIs (M2).** Allowlist `content://` only; reject `file://`, `android.resource://`, `javascript:`; verify MIME type or extension suffix before handing to the render path. | `MainActivity.kt:316-339` |

---

## Phase 2 — Threat boundary and redaction integrity

Fixes C2, H2, H3, H1, M1, M8, and the C3 toggle. **C2 is the highest-value fix in the audit —
a PDF with a scripted XFA form is currently reported clean and passes through sanitized.**

| # | Task | Files |
|---|---|---|
| 2.1 | **XFA detection in the audit (C2).** Add an explicit check of the AcroForm `/XFA` entry. The audit currently walks only `/AA` on the catalog, the form, and each field (`:109-115`). A document carrying XFA must report `threatsFound > 0`. | `logic/PdfSanitizerEngine.kt:88-156` |
| 2.2 | **XFA stripping in the purge (C2).** Remove the `/XFA` key from the AcroForm COS dictionary and reset default resources. Then re-run the audit on the *output* document and assert zero threats — the purge path is currently never verified against its own output. | `logic/PdfSanitizerEngine.kt:234-309` |
| 2.3 | **Complete the action blocklist (C2).** Add `ResetForm`, `Named`, `Rendition`, `RichMediaExecute`, `GoTo3DView` to both the audit's `processAction` and the purge's `hasMaliciousAction`. These two lists are currently maintained separately and have drifted. Consolidate into one shared set so they cannot diverge again. | `logic/PdfSanitizerEngine.kt:102,381` |
| 2.4 | **Vanguard fails closed on URIs (M8).** The audit counts `uriCount` (`:103`) and folds it into `isClean` (`:132`), but the Coordinator's block decision ignores it. Add the check. | `sandbox/SandboxCoordinator.kt:218` |
| 2.5 | **Resolve the cross-UID bind (H3).** *Blocked on Open Question 1.* If device verification confirms the bind fails, merge the native renderer into `PdfWorkerService` as a same-UID in-process call and delete `PdfNativeRendererService` + its AIDL + `NativeRendererCoordinator`. This also closes the H1 duplication: the ~28 host-process `PdfRenderer` sites (including the seekability probe at `utils/FileUtils.kt:116` and `logic/PdfEditor.kt:581`) remain the larger half of H1 and are tracked separately. | `sandbox/PdfNativeRendererService.kt`, `sandbox/NativeRendererCoordinator.kt`, `sandbox/PdfWorkerService.kt` |
| 2.6 | **Replace the cross-UID kill (H2).** `Process.killProcess(workerPid)` targets an isolated-UID process and silently fails. Move the timeout inside the worker as a cooperative watchdog that calls `stopSelf()`; the host detects a dead binder and falls back. Also close the never-closed `Channel`s. | `sandbox/SandboxCoordinator.kt:104,198,314,411,523,632` + `:26,119,250,340,437,541`; `sandbox/NativeRendererCoordinator.kt:58,64` |
| 2.7 | **Binder defense in depth (M1).** Add `enforceCallingOrSelfPermission` against a new `signature`-level permission on all three service binders. Currently the *only* control is `exported="false"`. | all three `Service` classes + `AndroidManifest.xml` |
| 2.8 | **Delete the `forensicSanitize` toggle (C3).** *Only after 2.5 lands and the raster path is confirmed working.* Remove the `Switch` from the screen, remove the `forensicSanitize` field from `RedactionConfig`, delete the `else` branch at `:343-345` that returns the visual-only document, and hardcode the raster path. | `ui/RedactionScreen.kt:54,323-336`; `logic/PdfRedactionEngine.kt:280-345`; `logic/PdfRedactor.kt:35`; `sandbox/SandboxCoordinator.kt:229` |
| 2.9 | **Correct the redaction messaging (C3).** Remove "permanently" from the success strings now that the visual-only path no longer exists; align with the actual behaviour. | `res/values/strings.xml:516,901` (and all 20 locales) |
| 2.10 | **Fix the forensic raster (M6).** `RGB_565` + JPEG q90 quantises the blackout boundary and destroys accessibility. Use `ARGB_8888` + `LosslessFactory`. | `sandbox/PdfNativeRendererService.kt:46-58` (or its post-2.5 merged equivalent) |

---

## Phase 3 — Cryptography and claims

| # | Task | Files |
|---|---|---|
| 3.1 | **Escape RFC-4514 characters in the DN (H5).** A signer name containing `,` produces a certificate claiming a third-party identity. Escape `,+"\<>;`, leading/trailing space, and `#`. | `logic/AndroidPdfCryptoSigner.kt:263` |
| 3.2 | **Add certificate extensions (H5).** Add `keyUsage` (digitalSignature, nonRepudiation) and `basicConstraints` (CA:false). Currently the certificate carries no extensions at all. | `logic/AndroidPdfCryptoSigner.kt:93-95` |
| 3.3 | **Stop writing signed bytes to `java.io.tmpdir` (M11).** Use the already-imported `CMSProcessableByteArray` over a bounded `ByteArrayOutputStream`. | `logic/AndroidPdfCryptoSigner.kt:52,57` |
| 3.4 | **Correct the signing claims (H5).** Remove "integrity verification" from `README.md` and the equivalent claim in the shipped store copy. State plainly that a self-signed certificate with an ephemeral, unpersisted key proves no identity, and that the app cannot verify signatures. The in-app dialog already says "self-signed" — align the rest. | `README.md:122`; `PLAYSTORE_RELEASE_NOTES_v2.0.0.md` |
| 3.5 | *(Optional, separate scope)* **Add PKCS#12 import + a real verification path.** The only structural fix for H5's trust problem. Explicitly out of scope for this plan unless requested. | — |

---

## Phase 4 — Data hygiene and input hardening

| # | Task | Files |
|---|---|---|
| 4.1 | **Guaranteed temp cleanup (H4).** The unredacted intermediate is written at `:276` and deleted only in the `finally` at `:355`; a process death leaves the pre-redaction document on disk. Restructure so the unredacted file is opened unlinked or written to a directory that is wiped on next launch regardless of startup path. | `logic/PdfRedactionEngine.kt:269-276,335,355` |
| 4.2 | **Randomise temp names (M4).** Replace `System.currentTimeMillis()` naming with `File.createTempFile` random suffixes. | `PdfRedactionEngine.kt:269,281`; `PdfToEpubEngine.kt:57,247`; `ComicBookEngine.kt:36`; `PdfCompressor.kt:89,109`; `SignatureEngine.kt:254-255`; `PdfAttachmentEngine.kt:180,248` |
| 4.3 | **Fix the cache key and add locking (M5).** Replace the 32-bit `uri.toString().hashCode()` key with SHA-256 — collisions currently cause one document to be rendered in place of another. Add exclusive locking around the cache write; two concurrent renders currently truncate each other's file and a third can open it mid-write. **Two divergent copies of this code exist — fix both or consolidate first.** | `utils/FileUtils.kt:126-133`; `logic/PdfEditor.kt:591-598` |
| 4.4 | **Add a `cacheDir` sweep for `pdf_seekable_*` (M5).** Full document copies currently persist indefinitely; `cleanupOrphanedCacheFiles` only prunes by extension and 1-hour staleness, and `pdf_seekable_` has no TTL. | `MainActivity.kt:341-365` |
| 4.5 | **Bound adversarial regex (M7).** Cap `query` length and add a match timeout around `matcher.find()`. Combined with the broken kill (H2), an unterminated backtrack currently wedges the worker permanently. | `logic/PdfRedactionEngine.kt:73-77,89-90` |
| 4.6 | **Bound page ranges (M7).** `pages.addAll((start..end).toList())` materialises the range *before* the bounds check, and `OutOfMemoryError` escapes every `catch (e: Exception)`. Validate first, and cap the range span. | `logic/PdfManipulator.kt:463-483` |
| 4.7 | **Clamp bitmap allocations (M7).** Clamp `targetWidth`; fix the inverted `coerceIn(1.5f, 2.5f)`, which force-upscales exactly the large pages the `maxDim` cap exists to protect (a 5000 pt page → ~225 MB ARGB). | `logic/PdfManipulator.kt:390,423`; `logic/PdfEditor.kt:285-288`; `logic/PdfFindAndReplaceEngine.kt:190-193` |
| 4.8 | **Data extraction rules (M9).** `allowBackup="false"` governs cloud backup but not device-to-device transfer on Android 12+. Add `android:dataExtractionRules` excluding `cache`, `files`, and `sharedpref` from both. | `AndroidManifest.xml:8`; new `res/xml/data_extraction_rules.xml` |
| 4.9 | **History: document *and* change the default (H7).** Disclosure alone leaves plaintext filenames on disk. Hash or truncate stored names, add a TTL, and flip `history_enabled` to default `false`. | `logic/HistoryRepository.kt:46-78` |
| 4.10 | **Mark the sensitive clip path (M3 follow-up).** Ensure no document path or name reaches logcat in release; `AppLogger` is already DEBUG-gated — verify the two raw `Log.w` calls at `MainActivity.kt:417,427` log only UMP error codes. | `MainActivity.kt:417,427` |

---

## Phase 5 — Documentation, manifest truth, and repo hygiene

Ordering matters: **each removal below is coupled to the code change that stops depending on it.**

| # | Task | Files |
|---|---|---|
| 5.1 | **Remove the inert Firebase meta-data (H7).** These flags govern Firebase/GA, which are not dependencies of this app — they are no-ops that read to a reviewer as an active control. | `AndroidManifest.xml:50-58` |
| 5.2 | **Add the AdServices config.** The manifest references `@xml/gma_ad_services_config` but `res/xml/` contains only `file_paths.xml` and `locales_config.xml`. Author a config that disables measurement and custom fragments, so the declared property resolves to a real, restrictive file. | `AndroidManifest.xml:60-63`; new `res/xml/gma_ad_services_config.xml` |
| 5.3 | **Rewrite `PRIVACY.md` (H7).** Correct: the "Zero Telemetry" claim (AdMob + UMP + Play Services ML Kit are live and initialise on every cold start), the "does not log document names" claim (plaintext history), the "never overwrites source files" claim (the destructive API is removed in 1.5, so the claim becomes true — keep them consistent), the "AES-128 and AES-256" claim (128 is unreachable), the "True Redaction" claim (true only after 2.8/2.10), and the camera-permission claim (no camera permission exists). Add the ad-stack disclosure required by `MANTRA.md:50`. | `PRIVACY.md` |
| 5.4 | **Remove the `google-services.json` dependency (L10), in this order:** remove the plugin and root `buildscript` classpath entry, verify the build, *then* `git rm --cached app/google-services.json` and add it to `.gitignore`. Reversing this order breaks every build. | `app/build.gradle.kts:8`; `build.gradle.kts:10-17`; `.gitignore` |
| 5.5 | **Move the screenshot fixture (L11).** Relocate `Annual_Report_2026.pdf` from `app/src/main/assets/` to `androidTest/assets/`, repoint the `isScreenshotRun` load sites, verify the Play screenshot harness still works, then `git rm` the main-source-set copy and root-level PDFs. Confirm the root sample PDFs are synthetic before purging history. | `MainActivity.kt:2283,2349`; `app/src/main/assets/`; repo root |
| 5.6 | **Purge the 53 MB AAB and repo junk (L12).** `git rm --cached` the release AAB, the one-off Python codemods, the UI-dump XMLs, `__pycache__`, `.kotlin/errors`, and the stale desktop `test_manifest.txt`. Note `.agents/` is already in `.gitignore` but fully tracked — needs `git rm --cached`. | `app/release/`, repo root, `.gitignore` |
| 5.7 | **License compliance (L9).** Stop stripping `META-INF/LICENSE*` / `NOTICE*` from the packaged APK — Apache-2.0 §4(a)/(b) requires them to travel with the work. Correct `THIRD_PARTY_CREDITS.md`, which lists Tess4J and Picocolo, neither of which is a dependency. Verify the build still succeeds after the exclusion removal (this exclusion was presumably added to fix a packaging conflict — check for a re-conflict). | `app/build.gradle.kts:79-93`; `THIRD_PARTY_CREDITS.md` |

---

## Validation

Per phase, in addition to the Phase 0 gate:

- **Phase 1:** add a test asserting a non-debug build ignores the `isScreenshotRun` extra.
  Verify 1.1(b) actually removes the premium path — grep for `IS_PREMIUM` consumers afterwards.
- **Phase 2:** this is the phase that most needs real tests, since the test suite was
  non-compiling until 0.6.
  - Fixture PDFs (generated at runtime, following the existing pattern in
    `ImageCompressorTest.kt:146-167`): one with a scripted XFA form, one with a `/URI`-only
    action, one with an `/OpenAction` JavaScript chain.
  - Assert the audit reports threats for each; assert the sanitizer's **output** re-audits to
    zero threats. That second assertion is new and currently missing.
  - Redaction: assert the output contains no extractable text in the redacted region
    (`PDFTextStripper` on the output), and that the raster path produces a non-empty file.
  - M8: assert a URI-only document is blocked by the Vanguard gate.
- **Phase 3:** assert the generated certificate parses, carries the expected extensions, and
  that a signer name containing `,` and `O=` produces a subject DN that does **not** contain
  those as separate RDNs.
- **Phase 4:** add regression tests for page-range bounds (`1-2000000000` must not throw
  `OutOfMemoryError`) and for a large-MediaBox page staying under a memory ceiling.
- **Phase 5:** confirm a release build succeeds and inspect the APK for the retained
  `META-INF` license files.

**Device verification required** (no emulator available in the planning environment) — confirm
before committing to task 2.5:
1. Does `PdfWorkerService` → `PdfNativeRendererService` `bindService` throw `SecurityException`
   across isolated UIDs? (`adb logcat` + `dumpsys package`, or `adb shell ps -A -o USER,NAME`
   to see the isolated UIDs.)
2. Does `Process.killProcess(workerPid)` return false across isolated UIDs?
3. Can an isolated-UID process read APK assets for `PDFBoxResourceLoader.init`?

---

## Risks

- **Task 2.5 is architectural.** Merging the native renderer into the worker changes the
  process model the manifest comments at `AndroidManifest.xml:29-31` describe. Those comments
  must be updated in the same commit, or the codebase will carry a second false claim.
- **Task 1.3 (`FLAG_SECURE`) breaks Play screenshot capture** unless the `preview_mode`
  escape lands in the same commit. Coordinate with 5.5.
- **Task 1.4 changes user-visible behaviour** for anyone relying on the current open-password-
  only behaviour. See Open Question 2.
- **Task 5.7 may re-trigger the packaging conflict** that motivated the `META-INF` exclusion
  in the first place. The BouncyCastle `jdk15to18` exclusion at `app/build.gradle.kts:102-106`
  is a separate, global, unpinned `exclude` — do not disturb it in the same commit.
- **5.4 and 5.5 both touch the build.** Sequence them in separate commits, each verified.

---

## Open questions

1. **Redaction sequencing — blocking task 2.8.** If the H3 bind is confirmed broken, deleting
   the `forensicSanitize` toggle makes redaction abort on *every* use (`:341`), converting a
   dishonest feature into a non-functional one. Options: (a) resolve 2.5 first, then delete the
   toggle — plan's default; (b) hide the redaction tool from navigation for one release, then
   restore it; (c) delete the toggle now and accept fail-closed aborts. **Recommendation: (a)**
   — it is the only option that ships a working, honest feature in a single release. Deferred by
   the user pending this plan; resolve before starting Phase 2.
2. **"Protect PDF" default permission policy (task 1.4).** Restricting print/extract by default
   is more honest but changes behaviour for users who protect a PDF and then print it
   themselves. Options: (a) restrictive by default with checkboxes to re-enable; (b)
   restrictive only when the owner password differs from the user password; (c) add a UI
   without changing the current default. **Recommendation: (a)** — it matches
   `strings.xml:453`'s existing promise.
3. **Ad stack: disclose or remove (task 5.3).** The privacy policy rewrite depends on this.
   Options: (a) keep AdMob/UMP, disclose fully in `PRIVACY.md`; (b) drop the ad stack, remove
   `INTERNET` and `BILLING` handling, and make the existing "offline/zero-trust" positioning
   literally true. **Recommendation: (b)** — it is the only option where the product claims and
   the code agree without qualification, and it removes an entire class of compliance exposure
   for a document-handling app. This is a business decision, not a technical one.
4. **H1 remainder.** Tasks 2.5–2.7 address the worker-side isolation story. The ~28 host-process
   `PdfRenderer` sites — including the seekability probe — are a separate, larger body of work
   to route all rendering through the sandbox. Recommend a follow-up plan; out of scope here.
5. **Ephemeral signing keys (H5).** Task 3.2 hardens the certificate but the key is still
   generated per-signature and discarded, so signatures remain unverifiable by the signer.
   Out of scope unless a PKCS#12 import path is wanted (3.5).
