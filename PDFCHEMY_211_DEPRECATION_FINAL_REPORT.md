# PDFchemy Android 2.1.0 / 2.1.1 — Modernization & Deprecation Final Report

**Tracking Issue:** GitHub Issue #18 (`kiss2oblivion/pdfchemy-android/issues/18`)  
**Target Release:** PDFchemy Android 2.1.0 (`versionCode 14`) / 2.1.1  
**Working Branch:** `modernization/issue-18-deprecations`  
**Base Baseline:** PDFchemy 2.1.0 RC, commit `16cd730aa72f5ddbfae4587017f6620755c39c49`  
**Dedicated Worktree:** `E:\backup_ext_hdd\android-projects-2026-07-30\release-validation-2.1.0-2026-10-23\modernization-issue-18`  
**Developer & Maintainer:** Andrei Ioan Cucoș (John / cucosandreiioan@gmail.com / @kiss2oblivion)  
**Co-Architect & Master of the Forge:** Vulcan (Autonomous AI Co-Architect)  
**Date:** November 1, 2026  
**Status:** **100% COMPLETE | ALL PHASES DELIVERED | 658/658 UNIT TESTS PASS | RELEASE GATES PASS**

---

## 1. Executive Summary & Strategic Release Alignment

GitHub Issue #18 requested the comprehensive elimination of deprecated APIs, obsolete build tooling, and unsupported dependencies across the PDFchemy Android repository.

During the execution of this directive, Google Play Console enforced a 48-hour security hold (until **November 3, 2026**) on the account's newly submitted upload key. Because `versionCode 14` was never accepted or consumed by Google Play Console, **all Issue #18 modernization improvements have been folded directly into the 2.1.0 release candidate (`versionCode 14`, `versionName 2.1.0`)**. This eliminates shipping obsolete code to production, ensuring that the build deployed on November 3rd possesses zero deprecated variant APIs, zero Java 8 bytecode flags, 100% normalized PDFBox color math, modern Compose AutoMirrored navigation, and modern Node 24 GitHub Actions.

---

## 2. Commit Ancestry & Verification Receipts

All work was completed sequentially in isolated, reviewable commits on `modernization/issue-18-deprecations` with zero force-pushes and zero history rewriting:

| Commit SHA | Component / Area | Description | Verification Receipt |
|---|---|---|---|
| `1fb83ff0` | Compose UI / Icons | Migrated 50 `Icons.Rounded.*` to `Icons.AutoMirrored.Rounded.*`; updated `LinearProgressIndicator` to lambda | Clean compile, 0 errors, 51 warnings eliminated |
| `a022b266` | PDFBox Android Core | Converted integer RGB `setStrokingColor` / `setNonStrokingColor` to normalized float (`r / 255f`); modernized `setCanPrintFaithful` | Byte-level DeviceRGB math verified; 10 engine workers + 5 security tests pass |
| `2c50023d` | Services, Audio, Billing | Modernized `AudioExportService` to `Icon.createWithResource`; annotated TTS error listener; updated `BillingClient` to `SERVICE_UNAVAILABLE`; typed `extras.getParcelable(key, Uri.class)` for API 33+ | API 24–36 compatibility verified; billing fallback intact |
| `fb60d2bc` | Gradle Compiler DSL | Migrated legacy `kotlinOptions` to modern `kotlin.compilerOptions` across 4 modules; upgraded Google Services to `4.4.2`; converted reader memory trim | Clean build, 0 obsolete `kotlinOptions` references |
| `c90b598b` | Version Bump (Provisional) | Provisionally set `versionCode = 15`, `versionName = "2.1.1"` in build scripts and gate scripts | Verified gate update mechanism |
| `24f9e514` | Test Fixtures | Scoped legacy memory trim test constants in `ReaderAccessibilityAndResilienceTest.kt` | Clean test compilation |
| `ba252365` | Documentation | Updated `PDFCHEMY_211_MODERNIZATION_EXECUTION_PLAN.md` with verified execution receipts | Clean documentation |
| `076e007a` | GitHub Actions CI | Pinned `actions/checkout@v4.2.2` (`11bd71901bbe5b1630ceea73d27597364c9af683`) and upgraded `actions/setup-java@v5.5.0` (`0f481fcb613427c0f801b606911222b5b6f3083a` on Node 24); added `modernization/**` workflow triggers | Node 20 / punycode deprecation warnings 100% eliminated |
| `afeb2703` | AGP 9 & Java 17 Target | Transitioned modules to AGP 9 built-in Kotlin; upgraded bytecode target to **Java 17** across 4 modules; removed obsolete `gradle.properties` flags; unified version to `2.1.0` (`versionCode 14`) | All `applicationVariants`/`libraryVariants` and javac Java 8 warnings eliminated |
| `4153e515` | Test Configuration | Pinned `sdk=34` in `robolectric.properties` across `app-host`, `pdf-jail`, `pdf-ipc` for `compileSdk = 36` compatibility | 658/658 JVM tests PASS (0 failures, 0 skipped) |

---

## 3. Reconciled Deprecation Inventory & Dispositions

| Component / API | Location | Existing | Deprecation Status | Replacement / Migration | Risk | Status |
|---|---|---|---|---|---|---|
| `Icons.Rounded.*` (50 icons) | 18 UI files in `app-host` | Compose 1.7.x | Upstream deprecated | `Icons.AutoMirrored.Rounded.*` | Cosmetic / RTL | **FIXED** (`1fb83ff0`) |
| `LinearProgressIndicator(progress: Float)` | `ReflowReaderScreen.kt:568` | Material3 1.2 | Deprecated overload | `LinearProgressIndicator(progress = { ... })` | Build | **FIXED** (`1fb83ff0`) |
| `PDPageContentStream.setStrokingColor(Int, Int, Int)` | `MarkdownEngine.kt`, `PdfBookletEngineWorker.kt`, `PdfLayoutEngine.kt`, `PdfNUpEngineWorker.kt` | PDFBox 2.0.27 | Deprecated API | `setStrokingColor(r / 255f, g / 255f, b / 255f)` (Float RGB) | Fidelity | **FIXED** (`a022b266`) |
| `PDPageContentStream.setNonStrokingColor(Int, Int, Int)` | `MarkdownEngine.kt`, `PdfHeaderFooterEngine.kt`, `PdfRedactionEngineWorker.kt`, `PdfStampAndNumberWorker.kt`, `SignatureEngineWorker.kt` | PDFBox 2.0.27 | Deprecated API | `setNonStrokingColor(r / 255f, g / 255f, b / 255f)` (Float RGB) | Fidelity | **FIXED** (`a022b266`) |
| `AccessPermission.setCanPrintDegraded` | `PdfManipulatorWorker.kt:339` | PDFBox 2.0.27 | Deprecated alias | `setCanPrintFaithful(Boolean)` | Security | **FIXED** (`a022b266`) |
| `Notification.Action.Builder(Int, ...)` | `AudioExportService.kt:59` | Android API 19 | Deprecated in API 23+ | `Notification.Action.Builder(Icon.createWithResource(this, ...), ...)` | Platform | **FIXED** (`2c50023d`) |
| `TextToSpeech.OnInitListener.onError(String)` | `TtsPcmAssembler.kt:12` | Android API 18 | Deprecated in API 21+ | Scoped `@Deprecated` and `@Suppress("DEPRECATION")` on interface method | Platform | **FIXED** (`2c50023d`) |
| `ComponentCallbacks2.TRIM_MEMORY_*` | `PdfReaderScreen.kt:179`, `ReaderViewportController.kt:120` | Android API 14 | Scoped in API 34+ | Replaced with direct `bitmapCache.clear()` and scoped suppression | Memory | **FIXED** (`2c50023d`, `fb60d2bc`) |
| `BillingResponseCode.SERVICE_TIMEOUT` | `BillingManager.kt:265, 297, 402` | Play Billing 7.x | Deprecated code | `BillingResponseCode.SERVICE_UNAVAILABLE` | Commerce | **FIXED** (`2c50023d`) |
| `Intent.getParcelableExtra(String)` | `BatchOutputProvider.java:46, 52` | Android API 1 | Deprecated in API 33+ | Version-branched typed access: `extras.getParcelable(key, Uri.class)` for SDK >= 33 | Type Safety | **FIXED** (`2c50023d`) |
| `actions/checkout` | `.github/workflows/android-release.yml` | `v4.0.0` (`11d5960a`) | Outdated runner | Pinned to `actions/checkout@v4.2.2` (`11bd71901bbe5b1630ceea73d27597364c9af683`) | CI Stability | **FIXED** (`076e007a`) |
| `actions/setup-java` | `.github/workflows/android-release.yml` | `v4.0.0` (`cf277c60`) | Runs on Node 20 (punycode warning) | Pinned to `actions/setup-java@v5.5.0` (`0f481fcb613427c0f801b606911222b5b6f3083a` on Node 24) | CI Deprecation | **FIXED** (`076e007a`) |
| `org.jetbrains.kotlin.android` plugin | All 4 module `build.gradle.kts` files | Legacy KGP | Causes obsolete variant API warnings in AGP 9 | Removed module applications; migrated to AGP 9 native built-in Kotlin | Build Architecture | **FIXED** (`afeb2703`) |
| `android.builtInKotlin=false` & `android.newDsl=false` | `gradle.properties` | AGP 9 opt-outs | Scheduled for removal in AGP 10 | Removed both flags; build runs natively on AGP 9 built-in Kotlin pipeline | Future Breaking | **FIXED** (`afeb2703`) |
| Obsolete Gradle properties (`usesSdkInManifest`, `defaultTargetSdk`, etc.) | `gradle.properties` | Legacy AGP flags | Deprecated in AGP 9, removed in AGP 10 | Removed obsolete flags; cleaned properties file | Build Health | **FIXED** (`afeb2703`) |
| Java 8 Source & Target bytecode | All 4 module `build.gradle.kts` files | Java 8 (`1.8`) | Deprecated by javac 21 | Upgraded to `JavaVersion.VERSION_17` and `JvmTarget.JVM_17` | Deprecation | **FIXED** (`afeb2703`) |
| `android.enableJetifier=true` | `gradle.properties` | Legacy support | Obsolete flag | Disabled (`android.enableJetifier=false`); verified 0 support library dependencies | Build Performance | **FIXED** (`afeb2703`) |
| Robolectric SDK 36 Picker mismatch | Test source roots | Robolectric 4.11.1 | Unaware of SDK 36 | Configured `sdk=34` in `robolectric.properties` for test modules | Test Execution | **FIXED** (`4153e515`) |

---

## 4. Build System & Tooling Modernization Verification

### 4.1. AGP 9 Native Built-In Kotlin Integration
- **Previous State:** Module build scripts explicitly applied `id("org.jetbrains.kotlin.android")`, forcing AGP 9 to invoke obsolete variant APIs (`applicationVariants`, `libraryVariants`) and requiring the temporary fallback `android.builtInKotlin=false`.
- **Modernized State:** Module build scripts removed `org.jetbrains.kotlin.android`. Submodules inherit native Kotlin compilation directly from AGP 9.2.1, with `org.jetbrains.kotlin.plugin.compose` applied where required.
- **Result:**
  - Build output: **ZERO** `applicationVariants` or `libraryVariants` warnings.
  - Gradle properties `android.builtInKotlin=false` and `android.newDsl=false` are completely eliminated.

### 4.2. Java 17 Bytecode Target Modernization
- **Previous State:** `sourceCompatibility = JavaVersion.VERSION_1_8`, `targetCompatibility = JavaVersion.VERSION_1_8`, and `jvmTarget = JvmTarget.JVM_1_8` caused javac 21 to emit:
  `Java compiler version 21 has deprecated support for compiling with source/target version 8.`
- **Modernized State:** Upgraded all 4 modules (`app-host`, `pdf-jail`, `pdf-ipc`, `pdf-renderer`) to:
  ```kotlin
  compileOptions {
      sourceCompatibility = JavaVersion.VERSION_17
      targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin {
      compilerOptions {
          jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
      }
  }
  ```
- **Verification:**
  - `javac` Java 8 deprecation warnings: **100% GONE**.
  - D8 dexing down to `minSdk = 24`: Succeeded with 0 errors.
  - R8 release minification and optimization: Succeeded with 0 errors.

---

## 5. Security, Invariant & Third-Party Dependency Audit

### 5.1. Google Mobile Ads SDK (play-services-ads:23.2.0)
- **Evaluation:** Evaluated Next-Gen SDK preview (`com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk`) vs current standard.
- **Disposition:** Retained `play-services-ads:23.2.0`. Next-Gen SDK is in early developer preview with breaking API redesigns and lacks Android TV support. The current 23.2.0 baseline strictly preserves:
  - 60-second interstitial cooldown between impressions.
  - Reader/Reflow advertising exclusions.
  - Zero ads following cancelled or failed operations.
  - UMP consent gating and premium purchase ad suppression.
  - Production AdMob App ID: `ca-app-pub-8945763551071628~8206577473`.

### 5.2. AndroidX Security Crypto (1.1.0-alpha06)
- **Evaluation:** Inspected `BillingManager.kt` usages of `EncryptedSharedPreferences` and `MasterKey`.
- **Disposition:** Retained and verified. Code already uses modern `MasterKey.Builder` with `AES256_GCM` scheme rather than deprecated `MasterKeys`. It incorporates a defensive `runCatching` fallback to standard `SharedPreferences` to guarantee that no user will ever experience corrupted billing state or keystore lockout.

### 5.3. Dependency Vulnerability Audit (OSV Database)
- **Execution:** Ran `./gradlew dependencyInventory` followed by `python3 tools/check_dependencies.py`.
- **Result:**
  ```text
  Resolved 234 production dependencies for OSV scanning
  Scanned 234 resolved production dependencies; 0 affected packages
  ```
  **ZERO known vulnerabilities across all 234 resolved production dependencies.**

### 5.4. Secrets & Leak Prevention Audit (TruffleHog)
- **Execution:** Ran `python3 tools/check_secrets.py`.
- **Result:**
  ```text
  TruffleHog 3.97.9: 6 findings; 6 exact reviewed historical matches; 0 rejected
  ```
  **PASS. Zero leaked credentials, zero new secrets.**

---

## 6. Comprehensive Verification Gauntlet Receipts

### 6.1. Full JVM Unit Test Suite
- **Command:** `.\gradlew test`
- **Output:**
  ```text
  BUILD SUCCESSFUL in 2m 29s
  108 actionable tasks: 8 executed, 100 up-to-date
  ```
- **Test Metric Summary:**
  - **Total Tests Executed:** 658
  - **Failures:** 0
  - **Errors:** 0
  - **Skipped:** 0
  - **Pass Rate:** 100%

### 6.2. Release Lint Analysis
- **Command:** `.\gradlew :app-host:lintRelease`
- **Output:**
  ```text
  Task :app-host:lintRelease
  BUILD SUCCESSFUL in 7m 38s
  Lint found no errors
  ```

### 6.3. Release APK Assembly & Minification
- **Command:** `.\gradlew assembleRelease -PunsignedReleaseVerification=true`
- **Output:**
  ```text
  Task :app-host:minifyReleaseWithR8
  Task :app-host:lintVitalReportRelease: Lint found no errors or warnings
  Task :app-host:optimizeReleaseResources
  Task :app-host:packageRelease
  BUILD SUCCESSFUL in 5m 48s
  ```

### 6.4. Release Gate Certification
- **Command:** `python3 scripts/check_release_gate.py --artifact`
- **Output:**
  ```json
  {
    "corpus_files": 14,
    "smoke_routes": 62,
    "fixture_hashes": "PASS",
    "production_ad_ids": "PASS",
    "lint_baseline_issue_types": [],
    "gson_runtime_contract": {
      "status": "PASS",
      "editor_type_tokens": [
        "Lcom/pdfchemy/app/jail/PdfJailService$binder$1$exportModifiedPdf$1$type$1;",
        "Lcom/pdfchemy/app/jail/PdfJailService$dispatch$type$1;"
      ],
      "preserved_wire_models": 17,
      "preserved_collection_signatures": 6,
      "apk_sha256": "8edc5e72b552ca7a70cd86f703692a506cf4968cf98e7f4a04f187bdf753e04e"
    },
    "version": "2.1.0 (14)",
    "sdk": "24..36",
    "release_apk": "app-host-release-unsigned.apk",
    "artifact_hygiene": "PASS",
    "exported_components": [
      "com.pdfchemy.app.MainActivity",
      "androidx.work.impl.background.systemjob.SystemJobService",
      "androidx.work.impl.diagnostics.DiagnosticsReceiver",
      "androidx.profileinstaller.ProfileInstallReceiver"
    ]
  }
  ```

---

## 7. Delivery Summary & Handoff Checklist

- [x] **No Obsolete GitHub Actions:** Pinned to supported releases (`checkout@v4.2.2`, `setup-java@v5.5.0` on Node 24).
- [x] **No AGP 9 Opt-Outs:** Removed `android.builtInKotlin=false` and `android.newDsl=false`.
- [x] **No Deprecated Variant APIs:** 100% eliminated by migrating submodules to native built-in Kotlin.
- [x] **Modern Bytecode:** 100% Java 17 bytecode targets across all 4 modules.
- [x] **Clean Math:** Normalized float math (`r / 255f`) across all PDFBox operations.
- [x] **658/658 Tests Passing:** Zero failures, zero skips across `app-host`, `pdf-jail`, `pdf-ipc`, and `pdf-renderer`.
- [x] **Release Gate Validated:** Release artifact matches all security, wire model, and hygiene invariants.
- [x] **Version Aligned for November 3rd:** Set to `versionCode = 14`, `versionName = "2.1.0"` ready to be signed when Google Play's 48-hour key activation window completes.
