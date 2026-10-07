# Android release certification

Wave 2 was conservatively merged into main at `aea2b53cb2c1b773057e40b0eb8f2f4b0dcff2b6` and pushed only to `repo-android`. Its tree exactly matches the previously certified feature tree. Main CI Run 29 is pending at the time of this preparation report; certification will be updated from completed job evidence. No Vanguard policy or Audio Export implementation was changed during release preparation.

## P1 — external release gates remain open

| Blocker | Exact file / evidence | Exact fix required | Verification |
|---|---|---|---|
| Upload certificate registration mismatch | `app-host/build.gradle.kts`; root `upload_certificate.pem`; `signing/README.md` records both full fingerprints | Confirm Play's **upload** certificate, then select its private key or perform the supported upload-key reset. Do not blindly replace signing material | Signed APK/AAB fingerprints agree locally; actual Play registration unknown |
| Real-world producer/original incident coverage incomplete | `app-host/src/androidTest/assets/vanguard-benign/manifest.json`; `corpus/README.md` | Add unchanged neutral native exports for remaining producers and the identified original incident PDF; require Clean/open/render on CI | Three native Word 2021 exports added with source/hash receipts; ten synthetic semantic samples preserved; Google Docs creation denied HTTP 403 |
| Signed physical-device smoke matrix not executed | `physical-device-smoke.md`; `scripts/package_release_smoke.ps1`; existing `reports/release-candidate-2026-10-06/tool-smoke-matrix.*` | Execute 61 routes, all 14 corpus PDFs and 15 audio scenarios on a signed/minified physical install; attach pass/fail evidence | Signed package generator tested; no connected local device; result sheets say NOT RUN |

## Resolved during this continuation

| Severity | Defect / exact file | Applied fix | Verification |
|---|---|---|---|
| P2 | `pdf-jail/build.gradle.kts`: external corpus not declared as Test input, so added PDFs could leave unit tests cached | Added named corpus directory input | Re-execution ran 175 unit tests, zero failures/errors/skips; three new native Word cases included |
| P2 | `BenignPdfCompatibilityTest.kt`: corpus test checked Clean/render but did not assert manifest taxonomy counts | Added known logical attachment and ordinary URI count assertions; require 14 fixtures | Instrumentation APK compiled; Android execution pending latest CI |
| P2 | Native producer evidence absent | Preserved native Word PDF bytes, source JSON, export receipts; added receipt/source/PDF checks to `scripts/check_release_gate.py`; pinned source LF bytes in `.gitattributes` | Three rendered pages visually inspected; hashes and release artifact gate pass |

## Local verification

- `testDebugUnitTest`: PASS, 175 tests after the corpus-input correction.
- `assembleDebug`, signed `assembleRelease`, `lintRelease`, `securityArchitecture`, `:app-host:assembleDebugAndroidTest`: PASS. Lint remains within the existing localization baseline.
- Signed `:app-host:bundleRelease`: PASS; embedded bundletool structural validation: PASS. APK signature verified; signed bundle certificate matches APK. JAR verifier warnings are recorded in `signing/README.md`.
- `scripts/check_release_gate.py --artifact`: PASS, 14 fixtures / 61 routes; no instrumentation fixtures/classes in release APK.
- `git diff --check`: PASS. Connected instrumentation not locally verified.

## P2 / submission work

Some locales remain English fallbacks, not completed translations. `foreground-service-declaration.md` provides actual mediaProcessing/dataSync behavior and a demonstration-video procedure; Console submission and video execution remain outstanding. Confirm version code 13 has not already been uploaded to this Play application.

Merge recommendation: Wave 2 is already merged. Public release recommendation: **NO**, pending the P1 evidence above and green latest-main CI.
