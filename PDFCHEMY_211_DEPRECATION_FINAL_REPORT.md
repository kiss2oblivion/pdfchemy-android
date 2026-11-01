# PDFchemy 2.1.0 modernization verification

Target: com.pdfchemy.app, versionName 2.1.0, versionCode 14, minSdk 24, targetSdk 36.
Original modernization branch: 52c49b8237bd0def29c7f6eb6956d6ab67280578, based on 16cd730aa72f5ddbfae4587017f6620755c39c49.

## Preserved work

Gemini's Compose AutoMirrored/progress changes, normalized PDFBox RGB operations,
AGP built-in Kotlin migration, Java/Kotlin 17 targets and explicit Robolectric API 34
configuration are retained. Independent clean replay of the corrected baseline passed
658 JVM tests. That receipt does not establish native, signed or Play acceptance.

## Corrections following independent verification

- Replaced the checkout downgrade and remaining Node 20 actions with immutable Node 24
  action versions. Added a manifest-runtime gate and four checker regression tests.
- Updated Google Mobile Ads 23.2.0 to 25.5.0 and UMP 2.2.0 to 4.0.0. Removed the
  retired AD_SERVICES_CONFIG resource reference. 61 focused JVM tests and architecture
  checks passed. Cooldown, cache expiry, Premium/consent guards and ad-free Reader/Reflow
  routing are preserved.
- Removed deprecated AndroidX Security Crypto from production. Existing encrypted
  entitlement records are read without alteration using their original authenticated
  format, then migrated to Android Keystore AES-GCM. Current invalid records never
  fall back to an old entitlement. No new plaintext fallback or missing-key regeneration
  occurs during reads. The old library exists only as an androidTest format oracle.
  55 focused JVM tests passed, including nine new store cases. Six actual API 36
  Keystore migration/tamper/missing-key cases passed; the installed candidate was restored
  and its APK hash checked. This was diagnostic instrumentation, not minified runtime.
- Used NotificationCompat and BundleCompat for platform compatibility. Scoped legacy
  memory callback suppressions and retained the required upstream TTS callback.

Receipts, including failed build attempts, are retained outside Git under
release-validation-2.1.0-2026-10-23/closure52-evidence. Signing/build helpers reuse the
existing identity; no credentials or key changes belong in this report.

## Acceptance boundary

The final exact-source CI URL, native counts, artifact hashes and main merge identity
are recorded in PR #17 after verification. No prior commit's green CI or signed APK
substitutes for those results. The API 34 Robolectric configuration does not replace
the required API 24/30/34/36 instrumentation matrix.

Physical ARM64/16 KiB runtime, Play upload-certificate/version eligibility, licensed
purchase/restore and account-dependent UMP/ad serving remain owner gates. No release
date, Play approval, actual ad revenue or successful billing transaction is asserted.
The owner authorized source integration and branch cleanup; publication remains separate.
