# SC-03 published batch outputs survive cancellation

Release: **HOLD**. Implementation and required local/full-CI batch verification complete. SC-04 and later findings have not started.

SC-03 application/test source is committed at `7390523c61b3eedbf0be91bc9f0696811b0c4626`, pushed only to the Android remote with the remote SHA matched. [Full CI run 39331074041](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39331074041) targets this exact commit. The completed workflow verifies this exact application/test source; the subsequent closeout changes documentation/evidence only.

SC-02 closes at Android-remote-verified `ce58c234b9d728063065699dbf05cd1dca7445ed`. Its application/test source `383f193aa5c5a0f5ebbd01e25f98d0add319c5f7` passes all nine required CI jobs, including 229 native methods per API24/30/34/36 (916 total). The SC-03 baseline APK is the retained local debug APK from that application source.

## Confirmed negative evidence

The focused terminal-state unit regression fails on the unchanged `TrackedOutput`: assigning a later lifecycle state demotes `PUBLISHED`, making a completed fresh-copy record eligible for cleanup. Its original failed XML/log are retained.

Two native API24 regressions run the actual PDF and image batch entry points with generated source files, isolated workers and a SAF-style provider implementing document query/create/delete operations with real file descriptors. Item 1 completes publication before item 2 blocks in destination open. The test verifies item 1 has nonempty bytes and its global freshness is retired, tracks an existing destination and a fresh cleanup sentinel, then cancels and releases the blocked writer. Sentinel deletion is a deterministic barrier after earlier tracked outputs are processed. Both methods fail on the unchanged application at `Published item 1 must survive cancellation of item 2`: production cleanup has deleted the completed output. See `sc03/write-grant-baseline-api24.log` and its exact app/test APK hashes.

Earlier fixture attempts are not security reproduction: the initial ungranted tree times out before any output open; adding an owner grant to an unrestricted provider still fails the library's writable-tree check; protecting both reads and writes prevents the initial control call. The final test-only provider allows control/read access, protects writes with MANAGE_DOCUMENTS and grants the host an explicit writable tree prefix. Its preflight requires an existing writable directory before starting each batch. Those timeouts/access failures and APK versions are retained separately. The release APK does not contain this test provider or its manifest.

## Change

Each tracked output now stores publication state atomically. Once it reaches `PUBLISHED`, later lifecycle assignments preserve that state, including cancellation racing publication. Constructor/default and property usage remain available to callers; no production caller uses the former data-class copy/equality methods. Existing cancellation, error and clearance eligibility checks consequently share the same terminal-publication rule. Fresh incomplete outputs remain eligible only under the existing provenance and writer-termination checks.

The stale-operation regression also verifies an old cancelled writer cannot delete a URI claimed by a newer operation. It uses a held writer and final cleanup sentinel to wait for the old cleanup, then checks seeded bytes and freshness remain owned by the newer operation. Existing writer-before-delete and failure-cleanup regressions remain in the selection.

## Local build and verification

| Layer | Result | Evidence under `sc03/` |
|---|---|---|
| Focused host unit selection | Four pass, zero failures/errors/skips | `fixed-unit.xml`, `fixed-validation.log` |
| Debug/test assembly and static audit | PASS; security audit scans 135 host files | `fixed-validation.log` |
| Actual PDF/image batch cancellation, API24 | Two pass in 1.296s; strict runner validator passes; empty crash buffer | `fixed-api24.log`, installation/crash logs |
| Same real batch selection, API36 | Two pass in 10.104s; strict runner validator passes; empty crash buffer | `fixed-api36.log`, installation/crash logs |
| Release lint and unsigned minified assembly | PASS in 6m 7s | `release-validation.log` |
| Release artifact/static corpus gate | PASS: 14 hashes, 62 smoke-matrix rows, IDs, version/SDK, manifests and package hygiene | `release-gate.log`; matrix completeness does not mean 62 UI routes were executed |

The interrupted local executions were recovered on 2026-10-20: completion logs, strict results and current retained/build-output APK hashes were verified before archiving. No build rerun is claimed for that recovery. `local-verification.json` records raw source/artifact identities. The raw log/XML manifest is checked before extension and against staged bytes before commit.

Full same-source CI and source commit/push/ref verification are complete. No production signing, physical-device workflow, SAF-picker UI automation or Play acceptance is claimed.

## Complete same-source CI

Run `39331074041` passes all nine jobs at `7390523c`. Architecture (`118329104726`), secrets (`118329104575`), dependencies (`118329104203`) and the complete JVM job (`118330632366`) pass. The dependent CI release job `118343501783` passes lint, debug/minified unsigned release assembly and corpus/matrix/artifact checks.

All downloaded native artifacts independently validate 231 unique completed methods per API24/30/34/36, zero failures/errors/skips, both real batch-cancellation methods and the retained eight Search/eight Editor security methods. This establishes **924 complete native executions** on this source. API24 also passes strict instrumentation-result validation with an empty crash buffer.

| API | Original execution job | Raw result |
|---|---|---|
| 24 | `118330632360` | `ci-api24-instrumentation.log`, install/crash logs |
| 30 | `118330632261` | `ci-api30.xml` |
| 34 | `118330632329` | `ci-api34.xml` |
| 36 | `118330632294` | `ci-api36.xml` |

The result hashes and final source/job identities are retained separately from the earlier checkpoints. Release remains HOLD for later findings and external acceptance gates.
