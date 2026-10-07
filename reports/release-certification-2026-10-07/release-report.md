# Android release certification

Wave 2 was conservatively merged into main at `aea2b53cb2c1b773057e40b0eb8f2f4b0dcff2b6` and pushed only to `repo-android`. Its tree exactly matches the previously certified feature tree. [Main CI Run 29](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37596663648) passed all nine jobs, including 197 completed instrumentation tests on each API 24/30/34/36 with zero failures and the final lint/debug/unsigned-release/R8/artifact gate. No Vanguard policy or Audio Export implementation was changed during release preparation.

The additional release-gate revision is `51d01283333a38a19861486457b6e89d8e417974`. [Run 30](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37599865983) passed all nine jobs, including 200 completed tests on each API 24/30/34/36 with zero failures/skips. Its final lint/debug/unsigned-release/R8 build passed in 14m 52s; the artifact gate verified 14 corpus PDFs, 61 smoke routes, fixture hashes and no test assets in release. The final documentation update contains no runtime/configuration/test changes. Local/remote main matched at the tested revision before that documentation update.

Commits created: `aea2b53` (Wave 2 merge), `51d0128` (native producer regression gate and release preparation), then a documentation-only certification update. Material changes: three native PDF fixtures and their manifest/test assertions; the Gradle corpus input; fixture/source verification; Word export, signed smoke packaging and bundle validation scripts; public signing certificate and release procedures. Production source and ProGuard are unchanged from the merge.

| CI gate | Merge Run 29 | Release-gate Run 30 |
|---|---|---|
| Architecture | PASS | PASS |
| Dependency security | PASS | PASS |
| Secrets | PASS | PASS |
| Unit tests | PASS | PASS |
| API 24 / x86 | 197 completed, 0 failed | 200 completed, 0 failed |
| API 30 / x86_64 | 197 completed, 0 failed | 200 completed, 0 failed |
| API 34 / x86_64 | 197 completed, 0 failed | 200 completed, 0 failed |
| API 36 / x86_64 | 197 completed, 0 failed | 200 completed, 0 failed |
| Final build / release artifact gate | PASS | PASS |

## P1 — external release gates remain open

| Blocker | Exact file / evidence | Exact fix required | Verification |
|---|---|---|---|
| Upload-key registration unverified; local public certificates differ | `app-host/build.gradle.kts`; root `upload_certificate.pem`; ignored `app-host/upload_cert_for_play_console.pem`; `signing/README.md` records full fingerprints | Confirm Play's **upload** certificate, then select its private key or perform the supported upload-key reset. Do not blindly replace signing material | Signed APK/AAB and the ignored Console-named PEM agree locally; root PEM differs; project search and Antigravity follow-up found no proof of actual registration |
| Real-world producer/original incident coverage incomplete | `app-host/src/androidTest/assets/vanguard-benign/manifest.json`; `corpus/README.md` | Add unchanged neutral native exports for remaining producers and the identified original incident PDF; require Clean/open/render on CI | Three native Word 2021 exports passed on all four CI APIs with source/hash receipts; ten synthetic semantic samples preserved; Google Docs creation denied HTTP 403 |
| Signed physical-device smoke matrix not executed | `physical-device-smoke.md`; `scripts/package_release_smoke.ps1`; existing `reports/release-candidate-2026-10-06/tool-smoke-matrix.*` | Execute 61 routes, all 14 corpus PDFs and 15 audio scenarios on a signed/minified physical install; attach pass/fail evidence | Signed package generator tested; no connected local device; result sheets say NOT RUN |

## Resolved during this continuation

| Severity | Defect / exact file | Applied fix | Verification |
|---|---|---|---|
| P2 | `pdf-jail/build.gradle.kts`: external corpus not declared as Test input, so added PDFs could leave unit tests cached | Added named corpus directory input | Re-execution ran 175 unit tests, zero failures/errors/skips; three new native Word cases included |
| P2 | `app-host/src/androidTest/java/com/pdfchemy/app/security/BenignPdfCompatibilityTest.kt`: corpus test checked Clean/render but did not assert manifest taxonomy counts | Added known logical attachment and ordinary URI count assertions; require 14 fixtures | Instrumentation APK compiled; all 14 corpus cases passed on API 24/30/34/36 |
| P2 | Native producer evidence absent | Preserved native Word PDF bytes, source JSON, export receipts; added receipt/source/PDF checks to `scripts/check_release_gate.py`; pinned source LF bytes in `.gitattributes` | Three rendered pages visually inspected; hashes and release artifact gate pass |

## Local verification

- `testDebugUnitTest`: PASS, 175 tests after the corpus-input correction.
- `assembleDebug`, signed `assembleRelease`, `lintRelease`, `securityArchitecture`, `:app-host:assembleDebugAndroidTest`: PASS. Lint remains within the existing localization baseline.
- Signed `:app-host:bundleRelease`: PASS; embedded bundletool structural validation: PASS. APK signature verified; signed bundle certificate matches APK. JAR verifier warnings are recorded in `signing/README.md`.
- `scripts/check_release_gate.py --artifact`: PASS, 14 fixtures / 61 routes; no instrumentation fixtures/classes in release APK.
- `git diff --check`: PASS. Connected instrumentation not locally verified.

## Submission requirements / P2 localization

Some locales remain English fallbacks, not completed translations. `foreground-service-declaration.md` provides actual mediaProcessing/dataSync behavior and a demonstration-video procedure; Console submission and video execution remain outstanding. Confirm version code 13 has not already been uploaded to this Play application.

Signed smoke ZIP: local `.security-runtime/PDFchemy-2.0.6-signed-smoke-51d0128.zip`, SHA-256 `cc09abec3371c0cb6c6efad6c7691de000a51b957a886e892d2575f3a08f517d`. Its receipt identifies the clean tested tree, verified signature and payload hashes; it includes blank results for 61 routes, 14 PDFs and 15 audio cases. APK/AAB files and logs remain local; no keystore/private key/password was published.

Merge recommendation: **YES — completed and certified**. Public release recommendation: **NO**, pending the P1 evidence above and required Play declaration/video submission. Known Wave 2 P0/P1 code defects: none identified by the completed verification; this does not certify unexecuted physical workflows or missing producer samples.
