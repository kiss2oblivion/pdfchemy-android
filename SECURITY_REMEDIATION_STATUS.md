# Android security remediation — 2026-10-01

Work order: the 2026-09-30 audit of `repo-android` at `cbafb998666219862b64e8d3d8dd1c172217b4e6`.
Implementation and verification were performed with Codex assistance. This record describes observed results; it is not a security certification.

**Release remains blocked: API 36 OCR crashes inside the platform NNAPI runtime when its hardware-service query runs under an isolated UID.** The original isolated boundary remains enforced. The first CI run passed architecture, dependency security, unit tests and the complete API 24 suite; API 30 / 36 failed only OCR and the build job was consequently skipped. Subsequent fixes require a fresh final-commit CI run. The GitHub connector and Git credential manager work; the separate GitHub CLI token is invalid. CI results are authoritative for the final pushed commit.

## Implementation and evidence

| Finding | Change | Evidence / remaining verification |
| --- | --- | --- |
| SEC-001 / SEC-002 | Host engines became IPC facades; parsers, generators, image decoders and crypto live in worker modules. Shared DTOs, limits, staging and Binder contracts live in `pdf-ipc`. Host compile dependencies exclude parser libraries. | Original `securityAudit` retained; module DAG check and strict compiled-host bytecode tests added. All passed locally on the subsequent `51ad0d8` implementation. |
| SEC-003 | Every batch input requires exact hash/size arrays and full verification before dispatch. | Real Binder tests reject null, short, long, non-hex, zero, wrong-size and wrong-hash metadata, including either bad member of a two-input batch. |
| SEC-004 / SEC-005 | Intent, editor, reader and shared pickers stage immutable bytes, including when Vanguard is disabled. One stager enforces copy-time limits and registry provenance, preserves original display names, and reclaims partial/expired snapshots. Operation leases defer deletion until active users close. Coroutine cancellation closes the active input and removes partial snapshots. | Changing unknown-length provider fixture; staged-filename spoof; immutable-source mutation; exact 100 MiB and plus-one rejection; failed-batch/comparison cleanup; operation leases; cancellation of an input that ignores interrupts. All staging regressions passed on API 24. |
| SEC-006 / SEC-007 / SEC-018 | Both workers admit one job and reject overlap. Dedicated watchdog threads terminate their own processes at 120 s / 30 s. Redaction/Office pipelines use operation scopes and owned resources. | Jail UID, 12 BUSY responses, abort/rebind and real 120 s watchdog passed on API 24 and 36. Native isolated UID, actual 30 s process death, host survival and new-process recovery passed on API 24. |
| SEC-008 | Repair uses bounded prefix/suffix reads and FD scratch; OCR/editor renders use bounded sizes; image/CBZ decoding samples before allocation; signature bytes use FD transport and bounds checks. ML Kit is initialized explicitly with its known background state and ephemeral process-local preferences, avoiding inaccessible host-private SDK bookkeeping. | 8192-square image/CBZ fixtures remain within the raster budget; oversized signature rejected before decode. API 24 OCR output is searchable and the original Binder remains usable after delayed GMS callbacks, including the new preferences wrapper. API 36 reaches native initialization but crashes in `libneuralnetworks.so` / `getAllHalInstanceNames`; OCR compatibility and product feature preservation remain open release blockers. |
| SEC-009 | All worker output writers enforce byte quotas; accounting also covers aggregate outputs and operation writes. The host gateway also truncates seekable destinations after worker death or coroutine cancellation, since killed workers cannot run their own cleanup. | Real 250 MiB plus-one destination rejected and truncated; next worker request succeeds. A new API 24 regression observes seven actual output bytes before forced Binder death and before cancellation, verifies zero bytes afterward, and successfully rebinds. Providers can prohibit truncation; it remains best effort for such destinations. |
| SEC-010 | Shared scrubber walks bounded object/action graphs, including outlines, associated files, attachments, XFA and rich media, and re-audits serialized output against the requested purge scope. Default purges require no remaining carriers or metadata. Custom options preserve unselected features and report only selected removals. | Carrier, cycle/depth, automatic action, benign web-link and destination regressions; device sanitizer/redaction and custom/default purge tests passed on API 24. |
| SEC-011 | Strict jsoup `1.23.2` constraint retained; resolved artifact checked for the namespace implementation and adversarial XML behavior. | Audit wording says “through 1.23.2,” but the release contains upstream fix `862ba2f`. See [upstream PR](https://github.com/jhy/jsoup/pull/2556), [release notes](https://jsoup.org/news/release-1.23.2), and [release source](https://github.com/jhy/jsoup/blob/jsoup-1.23.2/src/main/java/org/jsoup/parser/XmlTreeBuilder.java). |
| SEC-012 | Structured X.500 name builder; signing scratch uses FD capabilities; Bouncy Castle belongs to jail. CMS certificate storage uses the correct certificate-holder store. | Literal hostile-name and ephemeral-key unit regression passed. Actual PDF CMS integrity verification is a device regression. Self-signed signing does not establish a trusted identity chain. |
| SEC-013 | History defaults off; opting out clears previous entries; release logging omits document paths. | Six history unit tests, including previously stored history removal. Opted-in history still contains private local metadata. |
| SEC-014 | Wrapper checksum; immutable action SHAs; independent secret and dependency jobs; fatal release lint. TruffleHog `3.97.9` release archives are SHA-256 pinned, with exact historical fingerprint review rather than broad detector/file exclusions. Bouncy Castle `1.86`, Jackson `2.18.11`, Guava `33.7.2-android`. | Ten secret-policy regressions passed. The full local reachable-history scan on `e78d145` completed with five exact reviewed unverified matches and zero rejected findings. These are public bundle signing digests, an ICU language-resource decoder false positive, and the bundled Firebase client identifier. Verified findings, verification errors, changed identity and incomplete scans fail. Cloud API allowlists were not inspected. CI must verify the updated gate. OSV matched no affected packages among 248 resolved production dependencies. |
| SEC-015 | Functional parser tests moved into the application instrumentation APK; malformed-input testing renamed accurately; real isolation/death/hang/provider/quota tests added. | Results below distinguish local JVM tests from actual Android runtime tests. |
| SEC-016 | 256 KiB parameter limit plus field/item/node/depth budgets; nesting checked before recursive JSON parsing; object and array contracts supported. | Bookmark array regression and hostile deep/trailing/string payload unit cases. |
| SEC-017 / RISK-001 | Unique operation files; explicit stream/FD cleanup; host-created, immediately unlinked scratch FD capabilities replace worker app-private paths. | Isolated scratch write/read/fsync and real parse/render passed on API 24 and 36. PDFBox uses a bounded 32 MiB main-memory buffer, not inaccessible disk-backed scratch. |

## Limits and product behavior

- 100 MiB per staged input; 250 MiB aggregate staged input; 64 staged documents.
- 32 batch input/output FDs; 64 scratch allocations; 250 MiB output budget; 500 MiB cumulative operation writes; 500 output files/pages.
- Raster size at most 2048 per dimension and 4,194,304 pixels; compressed signature at most 2 MiB.
- PDFBox scratch buffers at most 32 MiB. Some inputs accepted by the staging cap can exceed this parser budget and fail safely.
- Active UI selections remain available through the activity session. Finishing the activity releases them; leases protect admitted operations; process-start cleanup removes expired snapshots. Original documents remain separate from snapshots.
- Overlapping jobs receive `BUSY`; user batch flows run sequentially where needed.
- Thirty-eight inherited translation omissions remain in `app-host/lint-localization-baseline.xml`. Only those exact localization findings are filtered; security/correctness lint errors remain fatal. Other lint warnings remain technical debt.

## Validation

At the time this review record was prepared:

- 47 JVM tests passed on the subsequent `51ad0d8` implementation: 23 host, 5 IPC and 19 jail tests, with zero failures/errors/skips. The original architecture tests remain intact, with additional DAG and compiled-host bytecode checks.
- Architecture gates and fatal release lint passed.
- The initial full local API 24 suite completed with 132 / 134 passing. Two fixture failures were corrected without reducing test limits: PDF signature `/Contents` zero padding is parsed correctly; the bitmap churn fixture releases its Canvas before its unchanged 10 MiB heap-growth assertion. Both corrected fixtures passed focused verification. The complete remote API 24 suite then passed all 134 tests on `d37fca6`.
- Focused API 36 runs passed 17 compression/resource stress tests and 16 capability/staging/raster/worker tests. An earlier full API 36 run stalled with an unresponsive emulator after 121 tests; it is not counted as a successful full run.
- The full local API 36 suite completed with 133 / 134 passing; the failure was isolated OCR. Fixing ML Kit's inaccessible SharedPreferences removed that Java crash, after which the focused OCR regression exposed a platform NNAPI native null dereference. This is not counted as resolved or as a successful full run.
- The process-local OCR preferences regression passed two jail unit tests and the focused API 24 OCR device test. API 30 needs rerunning after this fix; the first run's crash was an inaccessible `UserManager` during SharedPreferences initialization.
- OSV matched no affected Maven packages among 248 resolved production dependencies. The updated pinned TruffleHog gate passed locally as described above; the original remote secret job failed on the reviewed historical detections.

Implementation commit: `9931c93` (the coupled module/engine migration and its regressions are atomic). CI commit: `4d095bd`. The final PR and its check results record subsequent validation; this file is a review-time snapshot, not a claim that pending checks passed.

Follow-up implementation commits: `e78d145` pins the scanner and exact historical review policy (ten policy tests and a successful complete local scan); `499843c` makes OCR SDK preferences ephemeral (two unit regressions and searchable OCR on API 24); `51ad0d8` adds host cleanup after worker death/cancellation (actual API 24 Binder regression plus all 47 JVM tests and architecture gates).

Reproduce the gates with:

```text
./gradlew :app-host:securityAudit securityArchitecture dependencyInventory :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest :app-host:testDebugUnitTest :app-host:lintRelease :app-host:connectedDebugAndroidTest --max-workers=2
python3 tools/check_dependencies.py
python3 -m unittest discover -s tools -p test_check_secrets.py
python3 tools/check_secrets.py
```

CI runs the complete instrumentation suite on API 24 / 30 / 36. API 30 is not installed locally. OSV Maven matching is advisory coverage, not proof that no vulnerability exists; the jsoup Git advisory also required the separate source/artifact check above.

## Release follow-through

The remediation is on `security/audit-remediation-2026-10-01` in [draft PR #1](https://github.com/kiss2oblivion/pdfchemy-android/pull/1), targeting `repo-android`. Require green architecture, secrets, dependency-security, unit, instrumentation matrix and build jobs. A decision on replacing the incompatible bundled OCR backend is pending; no replacement has been implemented or approved. Re-audit the resulting commit before release. No release was created.
