# PDFchemy 2.1.0 integration readiness

**Decision: HOLD for release.** This is the combined review candidate for PRs #12, #13, #14 and #15. Original PR branches remain unchanged. No main merge, release tag, deployment or Play action is authorized.

The last application-code commit is `de825ca1075121c0187f854a0c6c0f877da07a5f` (tree `fea3f54e4ec0c8b6eb9e9d79950619ec2f84f789`). Remote heads, bases and CI were refreshed on 2026-10-30 for the [new assignment](https://github.com/kiss2oblivion/pdfchemy-android/pull/14#issuecomment-6517180797); all four input heads still match this integration. This report adds documentation only. The report-bearing commit must also pass all nine required jobs; its immutable SHA and run URL belong in the draft PR's final verification comment, after CI finishes. The completed results below apply to the explicitly named code SHA, not to a future commit.

## Dependencies and provenance

Branch: `integration/2.1.0-readiness-2026-10-29`. PR target: `integration/2.1.0-2026-10-26` at `9ad4fdc0ec5c86d7af3f8b0a30f80603d5a93e36`. The existing isolated integration is retained because its inputs did not change; rebuilding it would discard reviewed conflict resolutions and signed-build corrections.

| PR | Exact input head | Original base | Latest run for that head |
|---|---|---|---|
| #12 | `31f744e0ccd7fa4b8eddf439a0c837e8d88eca36` | Shared release base | [40867756273](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40867756273): 4 native failures; build skipped |
| #13 | `2c188f6c5408a3b3a0bedf4c1ebe6ac024f36412` | Shared release base | [40477285048](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40477285048): 9/9 pass, attempt 2 |
| #14 | `fef22697ab4ecccffe3ecf75c9a956fb1355db35` | #13 | [40877576469](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40877576469): 9/9 pass |
| #15 | `0d2711d1dfca989ecc19da05f266f93a11a1fbff` | main `4b3c157f3458f7df00ea94044258d33c7449ced9` | [40953658656](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40953658656): 9/9 pass |

Integration order is **#13 -> #14 -> #12 -> #15**, followed by reviewed published #15 deltas and the focused integration corrections below. All input heads are ancestors of the combined head. Merges use two parents; no historic PR #1-#7 cherry-picks were replayed.

| Merge commit | Incoming PR/source |
|---|---|
| `0a1050170ef11a774e561c8b46384fa0013975ac` | #13 |
| `aa39bc3db93ac2f2b7c3f371f470b2fe4369b27f` | #14 |
| `970cce85af5996c9c9e470701b5418587fe08daf` | #12 |
| `933d5be3390019f981c800666ce2046bce9326e8` | #15 at `d336eb13d1cccc9ff987728e2c60b77fc88a9caa` |
| `93ce4eca32c7bab3ad8fe19d925918aae38dca6e` | #15 at `fd6bf9d1e0af7f67efa3d15ffa44027bbb2520c7` |
| `f68484b39a50fe7f55df7d34baff73381b0dbb9a` | #15 at `48a1f16f5a01ca07a9213d3098bc8d51d47cdaad` |
| `bcc5dc0f254a73a35d0f6036c7d2637b4e0ae182` | #15 final input `0d2711d1dfca989ecc19da05f266f93a11a1fbff` |

Both parents, each focused commit's changed files, preserved security blobs and the full 153-file application-code integration diff are recorded in [integration evidence](reports/integration-2.1.0/validation-evidence.json) and [changed files](reports/integration-2.1.0/changed-files.txt). The new reporting files are listed separately there.

## Conflicts and compatibility

| Overlap | Resolution retained on the integration branch |
|---|---|
| #12/core `WorkerResponseValidator.kt` | Bounded streaming CSV state machine plus Gemini's typed contract and actual parser. Exhaustive parity over 55,987 short inputs plus logical rows, quoted newlines, escaped quotes and decoded bounds. |
| Initial #15 navigation, Reader, organizer, history and identity | Ten conflicts: MainActivity, HistoryRepository, AcroFormScreens, MainViewModel, OrganizeScreens, PageOrganizerScreen, PdfReaderScreen, ToolSearch, DocumentIdentity and base strings. Preserve external-URI admission, fresh-copy output, Editor sessions, source leases, organizer request generations, content-hash V2 identity/migration and complete tool catalog. |
| #15 localization delta | Union of release keys and 14 Reader keys in 20 locales. Duplicate keys rejected before commit; resource checks and release lint pass. |
| #15 startup deltas | MainActivity, Reader lifecycle tests and PdfReaderScreen reconciled with staged-source ownership, session positions and cancellation. Published layout helper retained with stronger native nonzero-position regression. |
| Worker/storage/publication and exports | Sixteen protected core files match #14 exactly as Git blobs, including BoundedWorkerStorage, CapabilityIo, JailScratch, publication registry/coordinator and Editor/Deskew/Office/redaction engines. |
| Gemini table extraction/parser | Both production blobs match #12. Shared CSV admission and host sharing were corrected without changing its worker/parser. |
| Dependencies/configuration | Combined minimum API 24 / compile and target API 36; R8/resource shrinking active. Billing/ads/UMP manager changes originate from Gemini. |

## PR #12 failure isolation

All four failing jobs were freshly read from run `40867756273`, head `31f744e0ccd7fa4b8eddf439a0c837e8d88eca36`:

| API | Job | Actual failing regression |
|---|---|---|
| 24 x86 | [123428564202](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40867756273/job/123428564202) | `testOptimizeFastWebViewFailsExplicitlyWhenLinearizationUnsupported`: worker Permission denied |
| 30 x86_64 | [123428564170](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40867756273/job/123428564170) | Same regression; `/proc/self/fd/50` EACCES |
| 34 x86_64 | [123428564341](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40867756273/job/123428564341) | Same regression; `/proc/self/fd/72` EACCES |
| 36 x86_64 | [123428564189](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40867756273/job/123428564189) | Same regression; `/proc/self/fd/88` EACCES |

This is a reproducible core dependency failure, not emulator instability. #12 still contains the old pathname readback; #13 supplies explicit unsupported Fast Web View generation before destination publication. The correction and original/target-preservation tests are already in the combined tree. The failing regression remains enabled. #12's standalone branch is still red; the integrated result is reported separately. [Gemini handoff](https://github.com/kiss2oblivion/pdfchemy-android/pull/15#issuecomment-6517282934) assigns this correction to core and avoids a duplicate rewrite.

## Confirmed integration defects and regressions

Severity describes reproduced impact. Every finding below has a focused correction and retained regression; there is no unresolved reproduced critical/high finding in this set. This does not close the separate risks and owner gates in the checklist.

| Severity / cause | Correction commit | Before -> after evidence |
|---|---|---|
| High: concurrent host renders hit the deliberately single-flight isolated renderer with BUSY | `e13ea43b0ca903ea1a95dbb94e1d33427b6b92e9` | Native 2 tests/1 failure -> 23 native + 3 JVM permit tests pass; signed four-page Reader works. Cancellable admission and idempotent permits retain worker isolation/quotas. |
| High: Reader hides its list while awaiting a scroll that needs the first layout | `e577ccbe83e463ccd2767f6422674aaf3672c5c7`, upstream helper merged in `bcc5dc0f` | Three reproduced Reader native failures -> 11 focused native pass; page 3/73-pixel position test retained on four APIs. |
| High: Reader UI disposal retires a snapshot still held by its model; saved tool return route lost | `c51d3930055ef78b38a285a8967c6f81450e55e0`, `61eb4ed5bac9a55093eddf4b9e1ee362f84c3b58` | Original 3/1 and 2/1 failing native phases -> 6 and 3 focused native pass, plus JVM lease tests. Model owns and releases the source lease. |
| High: post-process-death recovery saves a retired URI for the next Activity recreation | `3c216911413d9c03030d43bf2612ee64928d2b45` | Signed bcc5dc0f reproduced File Inaccessible; native original saver 1/1 fails -> focused 5/5 pass. Save only the matching live owned snapshot. Final signed recovery-then-recreation passes. |
| Medium: R8 renames/merges classes used as saved route names | `de825ca1075121c0187f854a0c6c0f877da07a5f` | Same portable release driver fails signed 3c216911 and passes final category/Office restoration, 2 cases; 9 focused JVM tests. Explicit stable identifiers for all 62 routes, no R8 weakening. |
| Medium: startup age cleanup deletes completed recipient shares, including opened copies | `c8c2e708bae5dacf3284efedfca5c867a0a0aa98` | Original 2 failures -> 15 focused native pass. Completed shares excluded from automatic expiration; explicit reader-safe cleanup retained. |
| Medium: actual Table Extractor screen bypasses retained-copy quotas | `e8c2f8bc3e94537e5c8fbf6c87da8f65fee332dd` | Actual-screen 65th share launches chooser before fix -> 19 focused native pass, including CSV decode/MIME, cancellation and partial-write cleanup. Same 64-copy/250 MiB budget and active-reader leases. |

Additional test commits: `18087454f75416201d4a8e871c4ffc76d97eb5db` makes actual parser parity and saved-Bundle startup fixtures deterministic; `4e125f094f8fe9273155d1c0e5c6e435fac0679f` tests serialized cyclic/deep malicious PDF graphs, output preservation and subsequent safe-document recovery. Test-fixture diagnostic failures are not counted as product reproductions.

## Completed validation of the code SHA

[Run 40970821297](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297) tests `de825ca1075121c0187f854a0c6c0f877da07a5f`. Status refreshed on 2026-10-30:

| Job | Status | Evidence |
|---|---|---|
| secrets | PASS | [123786811571](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123786811571) |
| architecture | PASS | [123786811652](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123786811652) |
| dependency-security | PASS | [123786811802](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123786811802) |
| JVM tests | PASS | [123788385424](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385424) |
| Android API 24 x86 | PASS: 420, zero failures/errors/framework skips | [123788385461](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385461) |
| Android API 30 x86_64 | PASS: 420, zero failures/errors/framework skips | [123788385450](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385450) |
| Android API 34 x86_64 | PASS: 420, zero failures/errors/framework skips | [123788385437](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385437) |
| Android API 36 x86_64 | PASS: 420, zero failures/errors/framework skips | [123788385423](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385423) |
| release lint/build/artifact validation | PASS | [123801561632](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123801561632) |

Local JVM results on that same code SHA: **627** (app-host 405, pdf-ipc 107, pdf-jail 115), zero failures/errors/skips. Local architecture/security, dependency inventory/checker, secret checker and release lint/build passed; 20 Python checker unit tests passed. Lint: 0 errors/fatals, 641 warnings. Dependency checker: 234 production dependencies, zero affected entries in its advisory set. Full-history TruffleHog: six exact reviewed matches, zero rejected findings.

GitHub native: **1,680 executions**. Four artifact ZIP hashes were checked against GitHub digests; 196 required methods across 42 classes were verified as completed on each API. Some optional external-corpus tests return early when files are absent; zero framework skips does not establish external-corpus coverage.

Security coverage exercises actual saved PDFs: redaction raw bytes/text/decoded streams/object streams and private aliases; original 300x200, A4/Letter, offsets and rotations; recursive action/metadata sanitization; isolated UID/service admission, worker death/timeouts/recovery; bounded pipe/storage and cancellation cleanup; SAF unknown/existing-target rejection and fresh-copy publication. It is not evidence that arbitrary SAF replacement is transactional.

Reproduce the CI-equivalent host checks with JDK 21 and Android SDK 36:

```text
./gradlew :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest :app-host:testDebugUnitTest :app-host:securityAudit securityArchitecture dependencyInventory :app-host:lintRelease :app-host:assembleDebug :app-host:assembleRelease --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process -PunsignedReleaseVerification=true
python -B -m unittest discover -s tools -p test_check_secrets.py
python -B tools/check_secrets.py
python -B tools/check_dependencies.py
python -B -m unittest discover -s scripts -p test_run_android_instrumentation.py
python -B -m unittest discover -s scripts -p test_release_gson_contract.py
python -B scripts/check_release_gate.py --artifact
```

The exact four-emulator commands are maintained in [.github/workflows/android-release.yml](.github/workflows/android-release.yml); API 24 uses the native runner with explicit end/count checks, API 30/34/36 use connectedDebugAndroidTest. Do not substitute host-model tests for native execution.

## Signed workflow evidence and limits

Standard signed/minified APK for code SHA `de825ca1`: SHA-256 `bb6f701c87129d7589ccabd58a1fc3ede014a277c1ecc859599214e11beeacd3`, 97,560,434 bytes, 2.1.0/code 14. Signing certificate SHA-256 `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b`. Existing key/configuration used; DEX/resources match the archived unsigned build. R8/resource shrinking remain enabled; release is neither debuggable nor test-only. Static ELF/ZIP checks pass for all ten 64-bit libraries; this is not a physical 16 KB runtime result.

Owned offline API 36 signed public-UI evidence:

- Nine Reader checks: real SAF entry, scroll, zoom/reset, background/launcher return, actual Activity recreation, Reader -> Editor -> Reader, host process death/new PID, later recreation after recovery, and unchanged source bytes.
- Two actual category/Office navigation recreation checks using [the committed release driver](scripts/smoke_release_navigation.py). Its unchanged hash reproduces the older signed failure.
- Two public PDF-to-PPTX exports, four pages each, mixed/cropped/rotated/blank fixtures. Eight slides contain eight valid picture relationships; independent python-pptx/Pillow/PyMuPDF reopening checks JPEG decoding, bounds, displayed aspect and image content. Original inputs and old same-name destinations retain their hashes. No Microsoft Office/LibreOffice renderer was used.

Signed native debug tests are not standard minified-release tests. Signed Reader continuity is not signed forensic Editor export verification. Final physical signed redaction, Play/Billing/UMP and the remaining public tool journeys stay open in [the release checklist](RELEASE_GATE_CHECKLIST_2_1_0.md).

## Ownership and final review

Codex owns this integration branch, cross-module corrections and exact-HEAD CI evidence. Gemini owns Reader/G01-G08 acceptance and its original branch; [the coordination comment](https://github.com/kiss2oblivion/pdfchemy-android/pull/15#issuecomment-6517282934) requests combined-PR verification and identifies retained-share Settings wiring. No Gemini response or new acceptance report is inferred from a green standalone PR.

The draft PR's final verification comment must identify the report-bearing SHA, every required job and the reviewed source delta. If any input branch advances, review its delta, integrate only deliberately, then repeat the affected regressions and full required CI. A documentation update is not permission to replace earlier evidence with a newer SHA label.
