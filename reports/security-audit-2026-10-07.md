# PDFchemy Android — Security Audit Report

**Date:** 2026-10-07
**Auditor:** Debug Agent
**Scope:** kiss2oblivion/pdfchemy-android (repo-android/main)
**Prior Work:** Extensive — `SECURITY_REMEDIATION_STATUS.md` (2026-10-01), release-blocker reports, UI/UX audit (2026-10-04)

---

## Executive Summary

The codebase exhibits a **strong security architecture** with defense-in-depth: isolated-process workers, tokenized operation ownership, host-only output publication with private snapshots, bounded quotas, strict wire validation, and CI-enforced gates. The existing remediation record (2026-10-01) is thorough and many findings are closed.

**New findings** (not in prior docs):

| ID | Severity | Finding |
|----|----------|---------|
| **SEC-01** | **CRITICAL** | SHA-256 hex validation rejects lowercase hex; parsing only verifies first 32 bytes |
| **SEC-02** | **CRITICAL** | `app-host/google-services.json` tracked in git — contains Firebase API key |
| **SEC-03** | **HIGH** | Upload signing certificate PEM tracked in `reports/release-certification-2026-10-07/signing/` |
| **SEC-04** | **HIGH** | `androidx.security:security-crypto:1.1.0-alpha06` (alpha) used for `EncryptedSharedPreferences` |
| **SEC-05** | **HIGH** | BillingManager: hardcoded "$4.99", `acknowledgePurchase` fire-and-forget, no signature verification |
| **SEC-06** | **HIGH** | UMP privacy choices not reachable from Settings → Privacy Policy dialog |
| **SEC-07** | **MEDIUM** | HistoryRepository stores full document URIs in plain SharedPreferences |
| **SEC-08** | **MEDIUM** | `keystore.properties` not gitignored (template is) |
| **SEC-09** | **MEDIUM** | ProGuard `-dontwarn javax.naming.**` retained for optional BC LDAP path |
| **SEC-10** | **LOW** | `flexmark-all:0.64.8` (2021) — check for CVEs |

**Existing release blocker:** API 36 OCR crashes in `libneuralnetworks.so` / `getAllHalInstanceNames` under isolated UID.

---

## Detailed Findings

### SEC-01: SHA-256 Hex Validation & Parsing Bug (CRITICAL)

**Files:**
- `pdf-ipc/src/main/java/com/pdfchemy/app/security/SecurityLimits.kt:43`
- `pdf-ipc/src/main/java/com/pdfchemy/app/security/StagedIdentity.kt:26`

**Issue:**
```kotlin
// SecurityLimits.kt:43
require(hash != null && hash.length == 64 && 
    hash.all { it in '0'..'9' || it in 'a'..'9' || it in 'A'..'F' })
```
The range `'a'..'9'` is a **descending CharRange** ('a'=97 > '9'=57). `CharRange.contains()` uses `start <= value && value <= end`, so **no lowercase hex char (a-f) ever matches**. Lowercase SHA-256 hashes (produced by `joinToString("") { "%02x".format(it) }`) are REJECTED.

Additionally, `StagedIdentity.kt:26`:
```kotlin
val expected = ByteArray(32) { hash.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
```
Only parses first 32 bytes (64 hex chars → 32 bytes). This is correct for SHA-256, but the bug in `SecurityLimits.requireIdentity` means validation fails for the host's own lowercase hashes.

**Impact:** If this code executes, all staging operations fail with "Invalid SHA-256". Either the code path isn't exercised in tests, or hashes are uppercase somewhere.

**Fix:**
```kotlin
// SecurityLimits.kt:43
hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
```
Add unit tests for lowercase/uppercase/mixed SHA-256.

---

### SEC-02: Tracked Firebase Config with API Key (CRITICAL)

**File:** `app-host/google-services.json` (tracked, 674 bytes)

**Contents:**
```json
{
  "project_info": { "project_id": "pdfchemy-tools", ... },
  "client": [{
    "api_key": [{ "current_key": "AIzaSyAzEwQyuhq7_RXIXfb9i5OfYyUh0-oPGEY" }],
    "client_info": { "mobilesdk_app_id": "1:472051479443:android:16cbf90985d2e3aaccee66" }
  }]
}
```

**Impact:** Firebase API key, project ID, and Android client ID are public in git history. While Firebase API keys for Android apps are generally considered "public" (they're shipped in the APK), best practice is to **not track** them and use build-time injection or environment-specific configs.

**Fix:**
1. `git filter-repo --path app-host/google-services.json --invert-paths`
2. Add `google-services.json` to `.gitignore`
3. Regenerate API key in Firebase Console
4. CI: inject via secret or use separate debug/release configs

---

### SEC-03: Tracked Upload Certificate PEM (HIGH)

**File:** `reports/release-certification-2026-10-07/signing/current-upload-certificate.pem`

**Contents:** X.509 certificate (public key) — `-----BEGIN CERTIFICATE----- MIIDlDCCAnygAwIBAgIJAIfFraxC9jZ7MA0GCSqGSIb3DQEBDAUAMHcxCzAJBgNV...`

**Impact:** This is a **public** certificate (upload key), so direct risk is low. However:
- Combined with the signing certificate mismatch (local `f7b12a17...` vs tracked `b12a125d...`), it indicates key management confusion
- `.gitignore` excludes `*.pem` but this file is tracked — inconsistency

**Fix:** Add `*.pem` to `.gitignore`; verify which upload key is actually registered in Play Console.

---

### SEC-04: Alpha security-crypto Dependency (HIGH)

**File:** `app-host/build.gradle.kts:162`
```kotlin
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

**Impact:** `EncryptedSharedPreferences` with `MasterKey.KeyScheme.AES256_GCM` / `AES256_SIV` used for billing entitlements. Alpha versions may have:
- Unfixed bugs in key derivation/encryption
- API instability
- No security patch commitment

**Fix:** Upgrade to `1.1.0` (stable) or `1.1.0-alpha07+`. Test on API 24+.

---

### SEC-05: BillingManager Issues (HIGH)

**File:** `app-host/src/main/java/com/pdfchemy/app/billing/BillingManager.kt`

| Issue | Location | Risk |
|-------|----------|------|
| Hardcoded price "$4.99" never updated from Play | Line 27, 108 | User sees wrong price; compliance risk |
| `acknowledgePurchase` fire-and-forget, no result check | Lines 159-165 | Unacknowledged purchases → auto-refund |
| No purchase signature verification | N/A | Relies on Play Billing Library only |

**Fix:**
```kotlin
// Remove hardcoded price
_premiumPrice.value = productDetails.oneTimePurchaseOfferDetails?.formattedPrice ?: "$4.99"

// Await ack result
val ackResult = billingClient.acknowledgePurchase(ackParams)
if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
    // Log/retry
}
```

---

### SEC-06: UMP Privacy Choices Not Accessible (HIGH)

**File:** `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt:395-437`

**Issue:** GDPR/UMP requires users to be able to revoke consent. Current flow:
- Custom `consent_dialog` (agree/decline) sets `has_consented=true` either way
- UMP `loadAndShowConsentFormIfRequired()` shows Google's form
- **No Settings entry point** to re-open privacy choices unless UMP returns `REQUIRED`

**Fix:** Add "Privacy choices" action in Settings → Privacy Policy dialog:
```kotlin
UserMessagingPlatform.showPrivacyOptionsForm(activity)
```

---

### SEC-07: Plaintext History Storage (MEDIUM)

**File:** `app-host/src/main/java/com/pdfchemy/app/logic/HistoryRepository.kt`

**Issue:** Opt-in history (default off) stores full document URIs + names in plain `SharedPreferences` as JSON. Not encrypted.

**Fix:** Use `EncryptedSharedPreferences` (same as BillingManager) or purge on disable.

---

### SEC-08: Missing `keystore.properties` in .gitignore (MEDIUM)

**File:** `.gitignore:83` — has `*.jks`, `*.keystore`, `*.pem` but **not** `keystore.properties`

**Risk:** If developer creates local `keystore.properties` with real passwords, it could be committed.

**Fix:** Add `keystore.properties` to `.gitignore`.

---

### SEC-09: ProGuard `-dontwarn javax.naming.**` (MEDIUM)

**File:** `app-host/proguard-rules.pro:34`

**Issue:** Retained for Bouncy Castle's optional LDAP CRL path (`CrlCache`, `X509LDAPCertStoreSpi`). Documented but weakens R8's dead-code elimination.

**Mitigation:** BC 1.87+ may drop this; app uses in-memory `JcaCertStore`. Track upstream.

---

### SEC-10: Old flexmark Dependency (LOW)

**File:** `pdf-jail/build.gradle.kts:73`
```kotlin
implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
```

**Issue:** 0.64.8 released ~2021. Check for CVEs (e.g., ReDoS in markdown parsing). Used for Markdown→PDF in isolated jail — input is user-supplied but processed in isolated process.

**Fix:** Upgrade or add allowlist for known-safe version.

---

## Existing Release Blockers (from Prior Docs)

| Blocker | Status | Source |
|---------|--------|--------|
| API 36 OCR NNAPI crash | **OPEN** | `SECURITY_REMEDIATION_STATUS.md`, `SEC-008` |
| Signing certificate mismatch | **OPEN** | Release-blocker report |
| Ten-file corpus provenance | **OPEN** | Release-blocker report |
| Physical device smoke test | **OPEN** | Release-blocker report |

---

## Architecture Strengths (Not Findings)

- **Isolated workers**: `PdfJailService`, `PdfNativeRendererService` — `isolatedProcess=true`, `exported=false`
- **Tokenized gate**: `WorkerGate` with admission handshake, dual watchdogs, stale-token isolation
- **Host-only output**: `HostOutputTransaction` — snapshots into private FDs before SAF commit
- **Wire validation**: `WorkerResponseValidator` — 82 operations, depth 8, 1 MiB limit, trailing data rejection
- **Input verification**: `DocumentStager` + `StagedIdentity` — SHA-256 + size at copy time, sealed read-only
- **Quotas**: 100 MiB/file, 250 MiB batch, 32 FDs, 120s watchdog, 500 MiB operation write
- **CI gates**: Architecture, secrets (TruffleHog 3.97.9 pinned), dependencies (OSV), API 24/30/36 matrix
- **Secrets hygiene**: `keystore.properties`, `*.jks`, `*.pem` gitignored; pinned Tesseract AAR hash

---

## Validation Commands

```bash
# Architecture & unit tests
./gradlew :app-host:securityAudit securityArchitecture \
  :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest

# Instrumentation (requires emulator)
./gradlew :app-host:connectedDebugAndroidTest

# Release lint & build
./gradlew :app-host:lintRelease :app-host:assembleRelease -PunsignedReleaseVerification=true

# Dependency scan
./gradlew dependencyInventory && python3 tools/check_dependencies.py

# Secrets scan (TruffleHog 3.97.9)
python3 tools/check_secrets.py

# SHA-256 parsing test (manual)
# Verify SecurityLimits.requireIdentity accepts lowercase/uppercase/mixed 64-char hex
```

---

## Recommended Priority Order

1. **SEC-01** — SHA-256 validation bug (may break staging silently)
2. **SEC-02** — Firebase config purge + key rotation
3. **SEC-04** — Upgrade security-crypto to stable
4. **SEC-05** — BillingManager: dynamic price, ack handling
5. **SEC-06** — UMP privacy choices entry point
6. **SEC-03** — Gitignore *.pem
7. **SEC-08** — Gitignore keystore.properties
8. **SEC-07** — Encrypt history storage
9. **SEC-09** — Track BC JNDI deprecation
10. **SEC-10** — flexmark CVE check / upgrade
11. **API 36 OCR** — ML Kit fallback or Tesseract fix

---

## Compliance Notes

- **GDPR**: UMP consent flow partially implemented; privacy choices entry point missing (SEC-06)
- **Google Play**: `app-ads.txt` present; billing price must match Console (SEC-05)
- **Firebase**: API key in git (SEC-02); analytics deactivated via manifest meta-data ✓
- **Network**: Only Google-standard libs (AdMob, UMP, Billing, ML Kit scanner) — no document network client ✓
- **Permissions**: `INTERNET`, `BILLING` only; no `MANAGE_EXTERNAL_STORAGE`, `RECORD_AUDIO`, `READ_CONTACTS` ✓
- **Backup**: `allowBackup="false"` ✓

---

## Files to Modify

| File | Finding(s) |
|------|------------|
| `pdf-ipc/src/main/java/com/pdfchemy/app/security/SecurityLimits.kt` | SEC-01 |
| `pdf-ipc/src/main/java/com/pdfchemy/app/security/StagedIdentity.kt` | SEC-01 (test) |
| `.gitignore` | SEC-02, SEC-03, SEC-08 |
| `app-host/build.gradle.kts` | SEC-04 |
| `app-host/src/main/java/com/pdfchemy/app/billing/BillingManager.kt` | SEC-05 |
| `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt` | SEC-06 |
| `app-host/src/main/java/com/pdfchemy/app/logic/HistoryRepository.kt` | SEC-07 |
| `app-host/proguard-rules.pro` | SEC-09 |
| `pdf-jail/build.gradle.kts` | SEC-10 |
| `pdf-jail/src/main/java/com/pdfchemy/app/jail/ocr/OcrBackendFactory.kt` | API 36 OCR |

---

**End of Audit Report**