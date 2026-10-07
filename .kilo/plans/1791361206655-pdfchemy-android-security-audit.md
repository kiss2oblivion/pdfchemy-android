# PDFchemy Android — Security Audit & Remediation Plan

**Repo:** kiss2oblivion/pdfchemy-android
**Branch:** repo-android/main
**Scope:** Security only (per user selection)

---

## Executive Summary

The repo has a mature security architecture with isolated-process workers, tokenized operation ownership, host-only output publication, and extensive instrumentation. CI enforces architecture gates, dependency scanning, secrets scanning (TruffleHog pinned), and full API 24/30/36 test matrices.

**Critical release blocker remains:** API 36 OCR crashes in platform NNAPI (`libneuralnetworks.so` / `getAllHalInstanceNames`) under isolated UID.

**New findings** (not in existing SECURITY_REMEDIATION_STATUS.md or release-blocker reports):

| ID | Severity | Finding | Evidence |
|----|----------|---------|----------|
| SEC-01 | P0 | `StagedIdentity.verifyAndRewind` hex parsing only validates first 32 bytes of 64-char SHA-256; lowercase hex hashes rejected by buggy `'a'..'9'` range | `pdf-ipc/src/main/java/.../StagedIdentity.kt:26` |
| SEC-02 | P0 | `app-host/google-services.json` tracked in git — contains Firebase API key (`AIzaSyAzEwQyuhq7...`), project ID, client ID | `git ls-files` confirms tracked |
| SEC-03 | P1 | `reports/release-certification-2026-10-07/signing/current-upload-certificate.pem` tracked — public upload cert; acceptable but should be gitignored | `reports/.../current-upload-certificate.pem` |
| SEC-04 | P1 | `BillingManager` uses `androidx.security:security-crypto:1.1.0-alpha06` (alpha) for `EncryptedSharedPreferences` | `app-host/build.gradle.kts:162` |
| SEC-05 | P1 | `BillingManager` hardcodes premium price "$4.99", never updates from Play; `acknowledgePurchase` fire-and-forget without result check | `BillingManager.kt:27, 108, 159-165` |
| SEC-06 | P1 | UMP privacy choices not reachable from Settings → Privacy Policy dialog; only when REQUIRED | `MainActivity.kt:395-437` |
| SEC-07 | P2 | `HistoryRepository` stores full document URIs in plain `SharedPreferences` (opt-in, default off) | `HistoryRepository.kt:18-47` |
| SEC-08 | P2 | `AppLogger` logs in release if `BuildConfig.DEBUG` check removed | `AppLogger.kt:10-25` |
| SEC-09 | P2 | ProGuard rules retain `-dontwarn javax.naming.**` for optional BC LDAP path — documented but weakens R8 | `app-host/proguard-rules.pro:34` |
| SEC-10 | P2 | `keystore.properties` not in `.gitignore` (template is) — if created locally would be tracked | `.gitignore:83` vs `keystore.properties.template` |

---

## Remediation Tasks

### P0 — Must Fix Before Release

- [ ] **SEC-01**: Fix SHA-256 hex parsing in `StagedIdentity.kt:26`
  - Change `hash.substring(it*2, it*2+2)` to parse full 64 chars (already does) but verify the `requireIdentity` hex validation at `SecurityLimits.kt:43` — change `'a'..'9'` to `'a'..'f'` (currently rejects lowercase hex)
  - Add unit test for lowercase/uppercase/mixed SHA-256 validation

- [ ] **SEC-02**: Remove `app-host/google-services.json` from git history and add to `.gitignore`
  - Use BFG or `git filter-repo` to purge from history
  - Add `google-services.json` to `.gitignore`
  - Rotate Firebase API key in Firebase Console
  - Verify CI builds without it (google-services plugin reads from file)

### P1 — High Priority

- [ ] **SEC-03**: Add `*.pem` to `.gitignore` (currently only `*.jks`, `*.keystore`)

- [ ] **SEC-04**: Upgrade `androidx.security:security-crypto` from `1.1.0-alpha06` to stable `1.1.0` or `1.1.0-alpha07+`
  - Test `EncryptedSharedPreferences` migration
  - Verify MasterKey `AES256_GCM` / `AES256_SIV` schemes still work

- [ ] **SEC-05**: `BillingManager`
  - Remove hardcoded `$4.99`; use `productDetails.oneTimePurchaseOfferDetails?.formattedPrice`
  - Await `acknowledgePurchase` result; handle `BillingResult` error codes
  - Add purchase token logging (redacted) for audit trail

- [ ] **SEC-06**: Wire UMP `showPrivacyOptionsForm()` into Settings → Privacy Policy dialog
  - Add "Privacy choices" action that calls `UserMessagingPlatform.showPrivacyOptionsForm(activity)`

- [ ] **API 36 OCR Blocker**: Replace Tesseract with ML Kit Text Recognition v2 for API 36+
  - `OcrBackendFactory` currently only returns `TesseractOcrBackend`
  - Add ML Kit fallback for API 36 where NNAPI crashes
  - Run instrumentation on API 36 emulator to verify

### P2 — Hygiene & Hardening

- [ ] **SEC-07**: Encrypt `HistoryRepository` storage or purge on disable
  - Use `EncryptedSharedPreferences` for history, or clear on `history_enabled=false`

- [ ] **SEC-08**: Verify `BuildConfig.DEBUG` is false in release (CI already checks `android:debuggable="false"`)

- [ ] **SEC-09**: Evaluate removing `-dontwarn javax.naming.**` once BC 1.87+ drops optional JNDI or migrate to in-memory cert store only

- [ ] **SEC-10**: Add `keystore.properties` to `.gitignore`

- [ ] **Dependency hygiene**: `flexmark-all:0.64.8` (2021) — check for CVEs; consider upgrade or replacement

- [ ] **ProGuard**: Verify `isMinifyEnabled=true` and `isShrinkResources=true` in release (CI `check_release_gate.py` validates)

---

## Validation Plan

| Check | Command | Pass Criteria |
|-------|---------|---------------|
| Architecture gates | `./gradlew :app-host:securityAudit securityArchitecture` | Zero violations |
| Unit tests | `./gradlew :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest` | All pass |
| Instrumentation (API 24/30/36) | `./gradlew :app-host:connectedDebugAndroidTest` (on emulator matrix) | 100% pass, zero failures |
| Release lint | `./gradlew :app-host:lintRelease` | Zero unbaselined errors |
| Dependency scan | `./gradlew dependencyInventory && python3 tools/check_dependencies.py` | No OSV matches |
| Secrets scan | `python3 tools/check_secrets.py` | Only reviewed historical findings |
| SHA-256 parsing | Unit test for `SecurityLimits.requireIdentity` | Lowercase/uppercase/mixed accepted |
| Price display | Manual verify on device with Play test account | Price matches Play Console listing |
| UMP privacy choices | Manual verify Settings → Privacy Policy → "Privacy choices" | Form opens |
| OCR API 36 | Manual verify on API 36 device/emulator | No NNAPI crash, searchable PDF produced |

---

## Open Questions / Decisions

1. **OCR backend replacement**: ML Kit Text Recognition v2 vs. Tesseract fallback? ML Kit requires GMS but avoids NNAPI crash. Tesseract is fully offline but crashes on API 36.
2. **Firebase API key rotation**: Coordinate with website team (same `pdfchemy-tools` project).
3. **Keystore.properties**: Current workflow uses env vars (`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) in CI; local file is template only. Confirm no local `keystore.properties` exists.
4. **Billing price**: Confirm with product whether hardcoded $4.99 is acceptable or must be dynamic from Play.

---

## Files to Modify

- `pdf-ipc/src/main/java/com/pdfchemy/app/security/SecurityLimits.kt` — fix hex range
- `pdf-ipc/src/main/java/com/pdfchemy/app/security/StagedIdentity.kt` — verify parsing
- `.gitignore` — add `google-services.json`, `*.pem`, `keystore.properties`
- `app-host/build.gradle.kts` — upgrade security-crypto, verify R8 config
- `app-host/src/main/java/com/pdfchemy/app/billing/BillingManager.kt` — dynamic price, ack handling
- `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt` — UMP privacy choices entry
- `app-host/src/main/java/com/pdfchemy/app/logic/HistoryRepository.kt` — encrypt storage
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/ocr/OcrBackendFactory.kt` — ML Kit fallback

---

## Rollback / Migration

- `google-services.json` purge: use `git filter-repo --path app-host/google-services.json --invert-paths`
- Firebase key rotation: Firebase Console → Project Settings → API keys → Regenerate
- `security-crypto` upgrade: backward-compatible; test on API 24+
- Price display: behind feature flag if needed

---

**Plan ready for implementation.** Covers all new security findings, existing release blockers, and validation steps.