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
- Used NotificationCompat for notifications. Foreign-UID SAF fixtures use platform-only,
  version-gated parcel access: host AndroidX classes are unavailable in that provider.
  Scoped legacy memory callback suppressions and retained the required upstream TTS callback.

## Additional closure reproductions and corrections

- CI run 41311384064 could not boot API 30: 6,701.91 MiB free versus 7,372.80 MiB
  required for userdata. CI now frees only the disposable hosted runner's unused .NET
  SDK and checks 10 GiB headroom after compilation. Two preflight regressions passed;
  all four API preflights passed in the next run. No timeout or test coverage was reduced.
- Run 41313471940 exposed a test-provider classpath regression introduced during review:
  BundleCompat was available at compilation but absent in the separate provider UID.
  Platform-only parcel access corrected it. Thirteen actual native provider, publication,
  cancellation and cleanup cases passed, including a new explicit foreign-UID roundtrip.
  The failed four-API run remains evidence, not acceptance.
- Signed OCR output independently reproduced fractional A4 rounding (595.28 became
  595.0 points) and lost crop/rotation structure. The new native regression reproduced
  the A4 mismatch on the original worker. OCR now reads original geometry using the
  transferred descriptor, preserves exact boxes/rotation/page units, and transforms
  the rendered image and invisible searchable text into that coordinate system.
  Raster limits, host publication ownership and worker isolation remain unchanged.
  Seven native OCR cases passed, including a six-page actual-export geometry/pixel
  fixture, worker continuity, progress, cancellation and malformed input. Seven focused
  OCR JVM cases also passed. The native receipt corrects an external collector's initial
  expected count of six; the unchanged framework log proves seven complete passes.

Those focused results are debug instrumentation. Final-source CI, rebuilt signed
artifacts and signed OCR revalidation are required before the main integration gate.

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
