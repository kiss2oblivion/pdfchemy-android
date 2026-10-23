# Historical Android security remediation execution - 2026-10-18

> This file preserves dated implementation checkpoints. Its old latest/pending/not-implemented statements are historical. Use [CURRENT_RELEASE_STATUS.md](../../CURRENT_RELEASE_STATUS.md) and the current checklist for the resulting candidate; later SC/FOLLOW corrections and full CI supersede the checkpoint below.

**Release: HOLD. SC-01 through SC-04 and SC-10 implementation/batch gates are complete.** Latest full CI at `4ec21c40` passes all nine jobs, 244 native methods per API24/30/34/36 (976 total), with one unchanged API34 startup retry retained under FOLLOW-09. See [SC-10 execution](SC10_EXECUTION.md). SC-05 through SC-09 and SC-11 through SC-14 remain open.

Implementation branch: `codex/security-remediation-2026-10-18`. Worktree: `PDFchemy-security-remediation-2026-10-18`. The original checkouts and their tracked/untracked work are preserved.

Fresh [2026-10-21 reanalysis](../security-code-reanalysis-2026-10-21/SECURITY_CODE_REANALYSIS.md) at `857c42b4` confirms five additional findings, SC-10 through SC-14, through six vulnerability-confirming probes. That audit changes no production code. [SC-10 implementation](SC10_EXECUTION.md) passes local gates, Android push/ref and complete same-source CI `39590800550`. SC-11 through SC-14 remain unfixed. Earlier fixed fixtures do not establish complete forensic-redaction assurance.

## Candidate and baseline identities

| Identity | Value |
|---|---|
| Implementation/audit baseline | `670578355c4e408c2468ba1564b0e1a366faaa0c` |
| Android remote main | `4b3c157f3458f7df00ea94044258d33c7449ced9` |
| SC-01 initial resource fix | `a1077e47abd833a24018ec455aff3cbe6465d823` |
| SC-01 nested-pattern/mask follow-up | `9d7dc22dd9ed38fb03ab50c52ac853f0e3fa72f9` |
| API24 CI installation repair / prior full CI source | `9e03c062f9cb797573430e1b5bd8d7b15f24b696` |
| Documentation/evidence checkpoint before closeout | `e24999296cfb2ff4b82a7ad89dec708c1d1fe187` |
| Form-tree / hidden-field / shared-stream follow-up | `593be048fc7b5e2a340940419d8eec0d9c944e3f` |
| Document metadata / accessibility / attachment follow-up | `414b053ac9c85e806392f7479b8b89b9f7a31d0a` |
| Outline structure-element reference follow-up / current CI source | `62d1b1e8dca4893d6889ee59a95598bf02b219db` |

Each listed implementation push went only to `kiss2oblivion/pdfchemy-android`, with the remote ref checked against the local SHA. The documentation/evidence checkpoints `1e20f8dc` and `e2499929` do not change application source, tests, scripts or workflows from CI target `9e03c062`. PR #2 remains open/draft on the earlier branch; no merge, upload or release is claimed.

The original audit probes, synthetic leaking PDFs, XML and logs are frozen under [the independent analysis](../security-code-analysis-2026-10-18/SECURITY_CODE_ANALYSIS.md), with `evidence/BASELINE_SHA256SUMS.txt` recording their original review-relative identities. Archived vulnerability-confirming probe sources are outside compiled tests. Historical baseline run `38270216907` is not verification of these fixes.

## Historical finding status at the recorded SC-10 checkpoint

| Finding | Status | Remaining gate |
|---|---|---|
| SC-01 editor forensic redaction | Current `62d1b1e8` is committed, Android-only pushed and remote-SHA verified; local gates and all four full CI native suites pass | None for this batch; later findings and release gates remain open |
| SC-02 | COMPLETE: source `383f193a`, all local gates and full same-source CI pass; 916 native executions | None for this batch; see [SC-02 receipt](SC02_EXECUTION.md) for the retained secret-verifier failure and unchanged successful retry |
| SC-03 | COMPLETE: source `7390523c`, local gates and full same-source CI pass; 924 native executions | None for this batch; see [SC-03 receipt](SC03_EXECUTION.md) |
| SC-04 | COMPLETE: source `7f7fe757`, local gates and full same-source CI pass; 944 native executions | None for this batch; see [SC-04 receipt](SC04_EXECUTION.md) |
| SC-05 through SC-09 | Not started | Their separate implementation/test/commit batches |
| SC-10 | COMPLETE at `4ec21c40`: local gates and full same-source CI pass | See [receipt](SC10_EXECUTION.md); open FOLLOW-09/10 remain separate |
| SC-11 through SC-14 | Confirmed defects; NOT IMPLEMENTED | Continue the remaining planned finding batches |
| Retained RA / external URI / SR-01 enforcement | Not started in source | Implementation/verification and risk disposition |
| FOLLOW-06 SR-01 documentation reconciliation | Complete | Stronger enforcement experiments or attributable owner disposition remain open under FOLLOW-07 |

## SC-01 implementation

Redacted editor pages receive fresh resources and replacement raster content; original annotations, thumbnail, metadata and related page attachments/actions are detached. Used-only resource cleanup removes inherited/shared unused resources and recursively handles Forms, annotation appearances, tiling patterns and soft-mask groups while preserving supported inherited resources and unrelated vector content. Failures abort export rather than publish an unsanitized fallback.

The initial resource checkpoint has nine production-helper and three Android regressions. Subsequent confirmed follow-ups extend coverage to 22 helper regressions and eight Android Editor regressions, including form values, XFA rejection, clipped shared streams, fail-closed aliases, document carriers and outline-linked accessibility text. Android tests invoke the real Editor -> gateway -> isolated worker export, inspect saved serialized/decoded objects and text, preserve unrelated vector text, and check opaque redaction pixels. The existing mixed-annotation rotation method covers eight geometry cases.

## Verification of resource/CI source checkpoint `9e03c062`

| Layer | Observed result | Evidence |
|---|---|---|
| Negative native reproduction on unchanged baseline | Three new SC-01 methods fail at sensitive-data assertions after successful export. A later unfiltered baseline run completes 216 methods with exactly those three failures | `sc01-baseline-api24.log`, `sc01-api24-full-baseline.log` |
| Worker JVM suite | 50 tests pass, including all nine resource regressions | `sc01-carriers-full-unit.log`, resource test XML |
| Host boundaries/static architecture | Five focused host boundary tests and architecture/static audit pass; complete current CI JVM job also passes | Local validation logs and `sc01-ci-repair-architecture.log` / `sc01-ci-repair-tests.log` |
| Local assembly/lint | Debug and Android-test assembly plus release lint pass. Local lint records 629 warnings and 38 unmatched baseline entries | `sc01-resource-carriers.log`, `sc01-final-validation.log` |
| Local final native focused runs | API24: four methods pass (19.454s). API36: four methods pass (39.983s) | `sc01-carriers-api24.log`, `sc01-carriers-api36.log` |
| Local complete API24 suite | 216 pass, zero failures/skips, strict runner result validation passes; crash buffer empty | `sc01-api24-full-fixed.log`, installation/runner/crash logs |
| CI helper result gate | Five unit tests pass. Actual passing native logs are accepted; real crash and baseline assertion-failure logs are rejected | `scripts/test_run_android_instrumentation.py`, architecture job log |
| Current complete CI native matrix | 216 pass per API24/30/34/36; 864 executions total, zero failures/errors/skips | Matrix below and `ci-repair-native-summary.json` |
| Current CI secrets/dependencies | Complete reachable-history secret and resolved-dependency jobs pass | `sc01-ci-repair-secrets.log`, `sc01-ci-repair-dependency.log` |
| CI release build/artifact gate | PASS: lint, unsigned minified release/debug assembly and artifact/corpus checks | Build job `117780283938`, `sc01-ci-repair-build.log` |
| Physical device, signed release, Play sandbox | NOT RUN | Final release gates remain open |

Test durations describe these runs, not performance benchmarks. Local final build verification used offline Gradle, one worker and in-process Kotlin. CI uses its recorded clean-runner commands. Source review, JVM, emulator, CI and physical-device claims are kept separate.

### Prior complete CI matrix

[Run 39160660276](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39160660276), attempt 1, targets `9e03c062f9cb797573430e1b5bd8d7b15f24b696`.

| API / ABI | Tests | Failures / errors / skipped | Result evidence | Job |
|---|---:|---|---|---|
| 24 / x86 | 216 | 0 / 0 / 0 | Strictly validated `sc01-ci-repair-api24-instrumentation.log`; push-install logs; empty crash buffer | `117774121419` |
| 30 / x86_64 | 216 | 0 / 0 / 0 | `sc01-ci-repair-api30.xml` | `117774121392` |
| 34 / x86_64 | 216 | 0 / 0 / 0 | `sc01-ci-repair-api34.xml` | `117774121458` |
| 36 / x86_64 | 216 | 0 / 0 / 0 | `sc01-ci-repair-api36.xml` | `117774121365` |

Uploaded results were downloaded and checked for complete counts and all three real-editor regressions. The native result validator checks unique per-test starts/completions, consistent declared counts, successful final instrumentation status and a nonzero passing summary. An adb shell exit code alone cannot pass the gate.

The complete workflow, including build job `117780283938`, succeeds at `9e03c062`. This is evidence for the tested resource cases and installation repair; it does not close the new form-tree case below.

## Additional SC-01 form-tree retention

A new production-helper test constructs a standard AcroForm text field on a redacted page. Replacing its page content and removing the page annotations still leaves the original private field value reachable through the document-level AcroForm. The serialized-output absence assertion fails on the resource-cleanup implementation. Evidence: `sc01-form-tree-negative.xml` and `sc01-form-tree-probe.log` (one executed test, one assertion failure). An earlier invocation failed at Gradle command-line parsing before test execution; `sc01-form-tree-negative.log` preserves that separate invocation error and is not counted as reproduction.

The real-editor API24 form-value test also failed against the retained resource-only APK. Production now validates the field hierarchy, rejects XFA before output writing, refreshes missing appearances, flattens supported visible form appearances before redaction, removes AcroForm and detaches external field references. Valid custom appearances are preserved. The form-only follow-up passes 55 worker tests and six focused native methods on local API24 and API36. These local runs are distinct from the earlier CI source checkpoint.

An additional unit probe confirms PDFBox flattening can make a NoView field selectable; `sc01-noview-negative.xml` and `sc01-noview-probe.log` retain the failing assertion. NoView widgets are now hidden before flattening. Hidden fields must not become visible page content.

## Additional SC-01 clipped shared streams

The actual API24 worker export succeeds but retains private decoded Form text when an untouched second page uses the same Form with a clipping rectangle that hides the private area. `sc01-shared-clip-baseline.log` records the failed sensitive-data assertion against the retained form-follow-up APK. This is a distinct defect from unused-resource retention: the second page legitimately uses the shared stream.

The current patch computes a bounded closure of pages sharing streams with redacted pages, rasterizes those dependent pages, and preserves independent vector pages. Embedded font streams can also create dependencies; affected pages consequently lose text selection. A final bounded traversal rejects output before destination writing if any replaced original stream remains reachable through the catalog or another carrier. It does not claim to identify every semantically duplicated private value elsewhere in a document. Independent text/vector preservation remains the implementation policy; whole-document image conversion has not been adopted.

Current regressions cover transitive stream sharing, independent vector text, catalog references and hidden fields. Native regressions additionally require visible content on a clipped dependent page to survive and failed catalog/XFA exports to preserve seeded existing destination bytes. Verification results for this patch must be recorded separately from the green `9e03c062` workflow.

The raster renderer now consumes a broker-owned, bounded scratch serialization of the validated/flattened document. This preserves newly generated form appearances on dependent pages; it also avoids rendering stale original field appearances. The descriptor is rewound, handed to PdfRenderer and closed with the scratch lease in cleanup. The existing aggregate write quotas apply to this additional serialization.

### Form/shared-stream checkpoint local verification at `593be048`

| Layer | Result | Evidence |
|---|---|---|
| Worker JVM | 58 pass, including 17 redaction helper regressions; zero failures/errors/skips | `sc01-prepared-render-validation.log`, `shared-stream-unit-results/` |
| Debug/test assembly and static checks | PASS; security audit scans 135 host files | `sc01-prepared-render-validation.log` |
| Real Editor export and annotation geometry, API24 | Eight focused methods pass in 29.483s; strict runner validator accepts; crash buffer empty | `sc01-shared-stream-api24.log`, install/crash logs |
| Same focused selection, API36 | Eight methods pass in 52.982s; strict runner validator accepts; crash buffer empty | `sc01-shared-stream-api36-retry.log`, install/retry-crash logs |
| Latest release lint | PASS in 3m 8s; 629 warnings and 38 unmatched localization-baseline entries | `sc01-prepared-render-lint.log` |
| Full local API24 suite | 220 pass in 837.592s; strict result validator accepts; crash buffer empty | `local-followup-api24/`, `sc01-shared-stream-api24-full-runner.log` |
| Full CI attempt at follow-up source | Superseded and intentionally cancelled after the document-carrier probes fail | [Run 39170654288](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39170654288); architecture, secrets and dependencies pass; JVM/native jobs are cancelled and no complete matrix is claimed |

API36's first attempt installed both APKs but failed to connect to the adb daemon before instrumentation. The failed connection/result-validation attempt is retained as `sc01-shared-stream-api36.log`; it executes zero tests and is not a passing run. A sequential retry completes the full eight-method selection. The separate catalog-reference negative export against the retained form-only APK succeeds incorrectly and fails the required rejection assertion; see `sc01-catalog-baseline.log`.

## Additional SC-01 document carriers

Three new helper probes fail because document information strings, XMP and accessibility ActualText remain outside page streams. A fourth probe confirms embedded/associated original files and alternate stream references survive. The original three-test and later four-test negative XML/logs are preserved separately. The first attachment invocation fails compilation on an unavailable `COSName.FIT` constant, executes no tests and is not negative security evidence; the corrected name lookup produces the four actual failed privacy assertions.

A valid tagged-PDF fixture (StructTreeRoot/ParentTree, page StructParents and marked-content MCID) reproduces the real Editor export leak on the retained `593be048` APK, API36. The native fixture is then extended with named/associated embedded originals and alternate metadata/file-stream references. Ordinary text, raw serialization and decoded saved streams must all exclude the private marker after export.

The current source strips document information, XMP/private application metadata and accessibility structure, including alternate references to removed carrier objects. It reuses the existing bounded attachment scrubber and detaches embedded originals through all recorded references. Named destinations and independent vector text are preserved. Form/XFA validation still runs before sanitization; unsupported exports retain the existing destination bytes. Accessibility structure, metadata, attachments and interactive fields are deliberately removed from secure redaction output.

| Latest document-carrier follow-up | Result | Evidence |
|---|---|---|
| Worker JVM | 62 pass, including 21 redaction helper regressions | `sc01-document-carrier-unit.log`, `document-carrier-unit-results/` |
| Debug/test assembly and static checks | PASS | `sc01-document-carrier-validation.log` |
| Actual Editor export and annotation geometry, API24 | Nine methods pass in 28.7s; strict validator accepts; crash buffer empty | `sc01-document-carrier-api24.log`, install/crash logs |
| Same selection, API36 | Nine methods pass in 67.909s; strict validator accepts; crash buffer empty | `sc01-document-carrier-api36.log`, install/crash logs |
| Latest release lint | PASS in 3m 41s; 629 warnings and 38 unmatched localization-baseline entries | `sc01-document-carrier-lint.log` |
| Local unsigned minified release | PASS in 4m 26s, explicit unsigned-verification property | `sc01-document-carrier-release-assembly.log` |
| Local release artifact/static corpus gate | PASS: 14 fixture hashes, 62 smoke-matrix rows, manifest/permissions/isolation/version and packaged-asset checks | `sc01-document-carrier-release-gate.log`; this checks matrix completeness, not execution of 62 UI workflows |
| Full CI at this document-carrier source | Superseded and cancelled for the confirmed outline-reference follow-up | [Run 39172047026](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39172047026); complete API24/30/36 results remain checkpoint evidence |

`sc01-ci-shared-stream-superseded.log` retains the cancelled workflow's available job output. Log retrieval exits nonzero only because the never-started dependent build job has no log; the status JSON records that job and the workflow as cancelled. This is not a new build or test assertion failure.

### Document-carrier checkpoint CI evidence at `414b053a`

Attempt 1 passes architecture, secrets, dependencies, the complete JVM job and the full native suites on API24/30/36. Each downloaded native artifact is checked for 221 unique passing methods, zero failures/errors/skips and all eight Editor security regressions; API24 additionally passes strict instrumentation-result validation and has an empty crash buffer. The intermediate summary records 663 native executions across those three APIs.

API34 job `117807981626` fails before app compilation or emulator startup because Gradle cannot resolve the unchanged pinned Foojay resolver plugin `1.0.0`. It executes zero tests. The failure log is retained; this is a dependency-resolution failure, not a failed Android security assertion. No upstream outage cause is established. The immediate targeted retry request is rejected while the workflow is active. After the first workflow completes, failed-job retry is accepted on 2026-10-19; attempt 2 API34 job `117969578196` builds successfully and starts the full suite on the same source. It reports 219/221 completed, zero failures/skips, before intentional cancellation for the confirmed outline-reference source follow-up. It is an incomplete execution and is not counted as a complete pass. Successful first-attempt gates are carried forward rather than counted as additional executions. The dependent CI build was skipped in attempt 1 and never verifies this checkpoint in attempt 2.

The first job-log retrieval is rejected by the CLI because it contains terminal escape sequences. That retrieval error is preserved separately; the actual job output is downloaded with explicit escape-sequence allowance and inspected with terminal controls stripped for display. Raw archived bytes are preserved.

| API / actual execution attempt | Verified tests | Evidence | Original execution job |
|---|---:|---|---|
| 24 / attempt 1 | 221, zero failures/skips | `sc01-ci-document-carrier-api24-instrumentation.log`, install/crash logs | `117807981697` |
| 30 / attempt 1 | 221, zero failures/errors/skips | `sc01-ci-document-carrier-api30.xml` | `117807981610` |
| 34 / attempt 2 | Incomplete: 219/221 reported before superseding cancellation | `sc01-ci-document-carrier-api34-superseded-retry.log`; attempt 1 executes zero tests | `117969578196` (cancelled) |
| 36 / attempt 1 | 221, zero failures/errors/skips | `sc01-ci-document-carrier-api36.xml` | `117807981592` |

Signing, physical-device workflows and Play verification remain unperformed. Local unsigned assembly and successful partial CI do not change the release HOLD.

## Additional SC-01 outline structure-element references

An outline can directly reference an accessibility structure element independently of the catalog's structure-tree root; see the primary [PDFBox outline API](https://pdfbox.apache.org/docs/2.0.4/javadocs/org/apache/pdfbox/pdmodel/interactive/documentnavigation/outline/PDOutlineItem.html). The added helper fixture confirms its ActualText survives through SE after the main tree is detached. Both the initial minimal graph and the canonical explicit-Type fixture fail the serialized-data assertion on `414b053a`; their negative XML/logs remain separate.

The completed extended Android test APK also reproduces this through the real API36 Editor export against the retained `414b053a` app. The initial native attempt overlapped test-APK packaging and passed an older fixture; it is explicitly excluded from negative evidence. After the test build completes, a fresh installation and retry fail the private-data assertion after successful export. The retained APK hashes identify the confirmed negative pair.

Production now records and detaches SE-linked element identities alongside the other metadata carriers. The extended native fixture requires both absence of private serialized/decoded content and preservation of the public bookmark title and destination to the sanitized page.

| Latest outline-reference source | Result | Evidence |
|---|---|---|
| Worker JVM | 63 pass, including 22 helper regressions | `sc01-outline-structure-validation.log`, `outline-structure-unit-results/` |
| Debug/test assembly and static checks | PASS | `sc01-outline-structure-validation.log` |
| Actual Editor export and annotation geometry, API24 | Nine methods pass in 21.587s; strict validator accepts; crash buffer empty | `sc01-outline-structure-api24.log`, install/crash logs |
| Same selection, API36 | Nine methods pass in 48.921s; strict validator accepts; crash buffer empty | `sc01-outline-structure-api36-retry.log`, retry install/crash logs |
| Release lint | PASS in 3m 24s | `sc01-outline-structure-lint.log` |
| Local unsigned minified release | PASS in 3m 43s at `62d1b1e8` | `sc01-outline-structure-release-assembly.log` |
| Local artifact/static corpus gate | PASS: 14 fixture hashes, 62 smoke-matrix rows and manifest/package hygiene checks | `sc01-outline-structure-release-gate.log`; no signing or execution of 62 UI workflows is claimed |
| Fresh full CI at latest source | PASS: 221 methods each on API24/30/34/36, 884 total; release lint/build/artifact gates pass | [Run 39228725164](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39228725164), exact pushed/remote-verified `62d1b1e8`; earlier CI at `414b053a` does not include this extended fixture |

Local native installation initially executes zero tests because the latest build uses the isolated ANDROID_USER_HOME and a different debug signing key. Certificate verification confirms the current host/test APKs match each other and differ from the retained baseline. After verifying the owned SC01Api24/SC01Api36 identities, only their synthetic host/test packages are reset and reinstalled. The mismatch, public certificate fingerprints and successful retries are retained. No production signing key is used or changed.

### Final-source CI native matrix at `62d1b1e8`

All four downloaded native artifacts independently validate 221 unique completed methods, zero failures/errors/skips and all eight Editor security regressions. API24 also passes the strict instrumentation-result validator and has an empty crash buffer. Annotation export parity remains covered. This establishes 884 complete native executions on the final SC-01 application/test source; it does not include the earlier checkpoint executions in that total.

| API | Actual execution job | Raw result |
|---|---|---|
| 24 | `117983165668` | `sc01-ci-outline-structure-api24-instrumentation.log`, install/crash logs |
| 30 | `117983165594` | `sc01-ci-outline-structure-api30.xml` |
| 34 | `117983165554` | `sc01-ci-outline-structure-api34.xml` |
| 36 | `117983165555` | `sc01-ci-outline-structure-api36.xml` |

Architecture (`117981247016`), secret scanning (`117981247055`), dependency security (`117981247104`) and the complete JVM job (`117983165682`) also pass on this same source. Their raw logs are retained. The dependent release build (`117992961163`) passes in 9m 58s, including release lint, debug/minified unsigned release assembly and the compatibility corpus/smoke-coverage/artifact hygiene gate. The complete workflow is successful; raw build output and final workflow/job identities are retained. No production signing, physical-device workflow or Play acceptance is claimed. `outline-structure-ci-native-summary.json` records the source, result hashes and counts.

## Failed, superseded and unresolved attempts

- The first focused worker JVM invocation failed because PDFBox glyph/font assets were missing from the test environment. Enabling Android resources for worker unit tests resolved that fixture failure. The failed log/XML remain archived; this was not negative security reproduction.
- Initial source run `39124161582` was superseded by the nested-carrier follow-up and cancelled. Its partial successful gates are not counted as a complete pass.
- Run `39124748605` at `9d7dc22d` passes complete JVM/static/secret/dependency jobs and all 216 native methods on API30/34/36. API24 fails before tests in both attempts: first ddmlib write timeout at 240000ms, then `ShellCommandUnresponsiveException`; both report `Failed to install-write all apks` and zero tests. The dependent build is skipped. Both failed job logs remain archived. GitHub initially rejected retry while that workflow was active; a failed-job retry was accepted after completion.
- The repair builds APKs before emulator startup and uses `adb install --no-streaming` plus the same unfiltered AndroidJUnitRunner suite on API24. Other APIs retain Gradle connected tests. The pinned emulator action splits script input into separate commands, so the conditional uses a validated folded shell command. No API, test or assertion was removed. Native installation/test results are uploaded on success or failure. See [Android command-line instrumentation](https://developer.android.com/studio/test/command-line).
- An additional local full API24 run on the Google Play image crashes during method 5 after four completed methods. The host crash buffer identifies `HTMLParserThrea`, SIGILL and `/system/app/Chrome/Chrome.apk`, with WebView 51.0.2704.90. The crash log and failed run remain archived. Matched clean-data baseline and fixed runs subsequently complete the full suite: baseline has the three expected redaction failures; fixed has 216 passes in 740.582s and an empty crash buffer. The baseline eight-method selection also passes. The original SIGILL is unexplained and remains under FOLLOW-05; successful retries do not establish a startup/consent repair. The owned emulator was stopped after verification.

## Evidence integrity

All evidence paths above are relative to `sc01/` beside this receipt. `artifact-and-source-hashes.json` identifies the initial baseline/fixed artifacts; `carrier-artifact-and-source-hashes.json` identifies the final local APK, test APK and resource-helper source. These final local identities were rechecked before the complete native run. CI source/run identities and downloaded native results are recorded separately in `ci-repair-native-summary.json`.

`RAW_LOG_XML_SHA256SUMS.txt` extends the earlier 39-file raw-evidence checkpoint with the downloaded CI results, additional negative probes and local follow-up runs. Each earlier hash is checked before extending the manifest; every staged log/XML blob is checked against the manifest before committing. Four early blobs were found to have LF normalization while their retained originals used CRLF: `sc01-unit-passing.xml`, `sc01-unit-retry.log`, `sc01-unit.log`, and `TEST-com.pdfchemy.app.jail.engines.RedactionResourcesTest.xml`. Only line endings differed. Intact originals were restaged under scoped raw-byte attributes; all 39 staged blobs matched the recorded hashes exactly before the correction commit. Earlier normalized blobs remain in history. No history or failed attempt was discarded.

`shared-stream-artifact-and-source-hashes.json` identifies the current raw local source, final compiled APKs and retained earlier probe artifacts. Its current source/artifact hashes are separate from both the resource-only manifests and previous CI source identity.

## Documentation and remaining release work

Root handoff/status documents point to the dated analysis, remediation plan/checklist and this receipt. Historical readiness/receipt files no longer claim unverified SR-01 owner approval. The [SR-01 reassessment](../security-code-reaudit-2026-10-10/SR-01_STORAGE_RESIDUAL_DECISION.md) corrects host PDF-validation claims, worker-local timer guarantees and premature rejection of hard file-size limits/seekable proxy descriptors. No hostile-worker experiment, stronger storage enforcement or owner risk acceptance is claimed by that correction.

All later source findings, the recorded legacy-WebView observation, signed physical workflows, Play Billing/UMP checks and final release/owner decisions remain tracked in [SECURITY_TODO.md](../../SECURITY_TODO.md). A completed SC-01 batch will not change the overall release HOLD.
