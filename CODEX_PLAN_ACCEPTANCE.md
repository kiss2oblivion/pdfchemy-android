# Codex C01-C06 acceptance

Baseline: `4aa857af6b8ccac2f1fd0cf93ed9761b5bb4389e`, PR #14, stacked on PR #13. Preserve prior fixes; no merge, tag, deployment or Gemini-owned implementation edits. Final commit identities and same-head CI results are recorded in PR #14 after verification, avoiding a self-changing acceptance SHA.

## C01 - Deskew preview request ownership

The implementation already landed in `f7872ff607e79cc385d4a85d2db8327b53860966`: monotonically increasing generations, previous-job cancellation, ResourceTransferOwner handoff, disposal and explicit failure state. The original routine reproduced three failures in four native cases (stale preview, retained preview on failure and disposal leakage); see CODEX_RELEASE_CLOSURE.md. No new production defect was found or speculative rewrite introduced.

Added actual native regressions for A/B/C completing C/A/B (only C remains) and 16 overlapping noncancellable producers with reverse completion. Every rejected bitmap is recycled; the current bitmap stays live until disposal; repeated close is safe. Existing replacement/failure/disposal tests remain.

Verification: `plan29-c01-accepted`, owned offline API36, **6 tests, 0 failures/errors/skips**. Build passed; original installed signed candidate restored and hash checked. The first native attempt stopped before tests because the build used the default debug certificate; an initial signing-helper command had an incorrect init-script path. Both failed attempts remain recorded. Rebuilt using the existing release-signing/debug-certificate helpers without changing keys. Production algorithm/export behavior unchanged.

Changed files: this report and `app-host/src/androidTest/java/com/pdfchemy/app/ui/DeskewPreviewIntegrityTest.kt`. Full four-API/final-source verification belongs to C06; this focused result is debug instrumentation, not a signed/minified runtime claim.

## C02 - Retained-share management

The backend implementation already landed at baseline `4aa857af`: locked accounting, 64-copy/250 MiB budgets, explicit selected cleanup, active reader/copy protection and partial-failure reporting. Existing baseline native cases passed; no destructive eviction defect was rediscovered or introduced. The missing explicit statistics were active producer/total transfer counts. Added snapshot-derived `activeCopyCount` and `activeTransferCount`, without changing retention or granting wider access.

Added a real foreign-UID regression opening the same URI twice, closing only one recipient stream, requesting cleanup while the other remains live, then reopening through the retained grant after both close. Cleanup never removes the active file. Counts reflect both readers, and active-copy statistics are checked while a producer is blocked. Existing halfway failure/cancellation, quota refusal, incomplete cleanup and source-byte checks remain.

Verification: `plan29-c02`, **23 native tests (10 management, 10 copy-integrity, 3 Reader sharing), 0 failures/errors/skips**, 26.401 seconds; app-host JVM **312 passed**; debug/test build passed; original installed signed candidate restored and hash checked. Full final-source matrix remains C06. No completed-share expiry or permission revocation. Existing storage-full error still explains that recipient copies were preserved.

Changed files: `RetainedShareStorage.kt`, `RetainedShareManagementTest.kt`, test-only `ShareRecipientProvider.java`, `reports/RETAINED_SHARE_API.md` and this report. Production DocumentActions locking/provider isolation remain intact. Explicit deletion of an inactive file prevents future opens; legacy unproven copies remain cleanup-protected.

## C03 - CSV boundary validation

Original reproduction: `plan29-c03-original.xml`, **16 JVM cases, 6 failures, 0 errors/skips**. The physical-line/toggled-quote validator accepted malformed quotes, overlong multiline cells, decoded escaped-quote overflow and excessive blank/whitespace records. Malformed files could return preview text. Golden semantics are adopted from Gemini's published PR #12 parser/fixtures at `5c1308ccfb3062d23918f14d734f17e417e77888`; the shared fixture contract and read-only snapshots were established before implementation. No private approval or integration is implied.

Implemented a constant-space state machine over the bounded input text, counting logical records, decoded cell characters, columns and total cells. Quotas remain 2 MiB, 5,000 rows, 128 columns, 1,024 decoded characters/cell and 20,000 cells. File reading also rejects growth/truncation after its size check. PdfGateway now requests CSV content validation on immutable host snapshots before worker ACK/destination publication. Snapshot reads use approved descriptor pread; the pipe/collector and host publication ownership model are unchanged. JailEngineBridge already delegates through PdfGateway.

Verification: **87 repo IPC JVM + 3 external pinned-parser parity + 312 host JVM tests passed**. The parity tests compare decoded golden values and all **55,987** short grammar inputs plus quota fixtures against the unmodified published parser snapshot. `plan29-c03`: **26 native tests, 0 failures/errors/skips** (3 actual CSV snapshot/publication cases, 13 host commit cases, 10 storage boundary cases). Invalid/oversized CSV cannot commit or alter existing destination bytes; valid multiline/escaped CSV publishes byte-for-byte. SecurityAudit/securityArchitecture and debug/test compilation passed. Original installed signed candidate restored and hash verified.

Changed files: `pdf-ipc/.../WorkerResponseValidator.kt`, `pdf-ipc/.../HostOutputTransaction.kt`, `app-host/.../PdfGateway.kt`, new IPC `CsvBoundaryValidationTest.kt`, new native `CsvPublicationBoundaryTest.kt`, `reports/CSV_BOUNDARY_CONTRACT.md` and this report. The external parity oracle is test evidence outside the checkout; repo golden/quota regressions run in CI. Gemini's parser/table worker/UI were not edited. Cross-branch integration must rerun parity if that parser changes. Full same-final-source matrix remains C06.

## C04 - SAF save callbacks and cleanup ownership

Confirmed an additional defect in saved-folder creation cleanup: it unregistered provenance and directly deleted the DocumentFile when handoff failed. Late cleanup could destroy a successfully published PDF; cleanup of a transaction-owned copy threw an ownership exception. Extracted that existing cleanup unchanged for native reproduction: `plan29-c04-original-accepted`, **3 cases, 2 expected failures**, including actual METADATA_WRITE export followed by creator cleanup. Abandoned fresh-copy cleanup already passed.

Correction uses OutputPolicy.deleteIfFresh, preserving published/claimed copies and retaining provenance when provider deletion fails. Both creation coroutine paths explicitly dispatch UI callbacks/errors on Android Main. Added actual Compose callbacks for saved-folder reuse, unavailable-folder fallback, repeated numbered names, an existing-URI-returning provider, and disposal during uninterruptible creation, alongside first-use/picker-cancel cases. Cancellation waits for the provider to return before cleaning its proven fresh copy; arbitrary provider interruption/rollback is not promised.

Accepted `plan29-c04-accepted`: **22 native cases, 0 failures/errors/skips** (7 preferred-save UI, 3 cleanup ownership, 4 fresh publication, 4 existing-destination preservation, 2 stalled cleanup and 2 Deskew copy publication). Published PDF bytes, original PDF bytes and same-name existing documents remain intact; abandoned creation is deleted without a stale callback. App-host JVM **312 passed** during this batch; debug/test builds passed; installed candidate restored and hash verified.

Earlier attempts retained: the initial baseline had fixture URI reuse after its synthetic provider rejected host deletion. Added a proper fixture delete operation, then reran the unchanged cleanup reproduction. First fixed 22-case run had two failures: disposal synchronization released the provider before actual recomposition, and an error Toast encountered a Compose test dispatcher without an Android Looper. The disposal test now waits for actual removal; production creation callbacks explicitly use Main.immediate. No sleep or timeout increase. Both regressions pass in the accepted run.

Changed files: `app-host/.../ui/PreferredDocumentCreator.kt`, native `PreferredCopyPublicationTest.kt`, new `PreferredCopyCleanupIntegrityTest.kt`, test-only `BatchOutputProvider.java` and this report. No Gemini-owned screen or resource changes.

## C05 - PowerPoint acceptance

Preserved the implementation from `663cdf429f1a75b2cce2009696347e1d0ceea0f3`; OfficeExportEngineWorker.kt is unchanged. Original evidence remains in CODEX_RELEASE_CLOSURE.md: six failures in nine cases, including slides with no picture and silent success on invalid/incomplete rendering.

Expanded native tests with blank A4/Letter pages, 180/270 rotation, nonzero crop offsets, a 20,000-by-20,000 JPEG header rejected before raster allocation, and a streamed payload over the real 250 MiB image budget. The latter allocates only a small reusable buffer and uses the existing production worker deadline, without increasing the original fault-test timeouts. Existing malformed/zero-page, renderer missing/count mismatch/exception/truncated JPEG, Word/Excel and four mixed-page placement cases remain. Synthetic artifact collection is opt-in, bounded to 512 KiB per fixture and API26+, entirely in androidTest code; API24 still runs all export assertions.

Accepted debug run `plan29-c05-debug-sequential`: **12 native tests, 0 failures/errors/skips**. Two real four-page exports independently reopened with python-pptx 1.0.2/Pillow and source PyMuPDF 2.0: eight page pictures, valid image/layout/master/theme relationships, crop/rotation aspect ratios, bounded centered extents, source-center pixel correspondence and save/reopen image/geometry preservation. Inspected all eight parser-based slide previews. This is independent parser verification, not Microsoft PowerPoint application runtime verification.

Signed/minified public UI verification **passed** on the owned offline API36 emulator (`plan29-c05-ui-verified`): public Create category -> PDF to PowerPoint -> Vanguard picker -> real SAF folder grant -> numbered fresh copy. Both mixed and blank/A4/Letter/180/270 fixtures exported successfully; every source and same-name old destination stayed byte-identical, including after the second export. Independently parsed the two actual signed-path PPTX files: eight slides/eight pictures, correct relationships/geometry/pixels and round-trip preservation; inspected all previews. Original installed APK restored and hash checked.

Signed release artifact, built from unchanged production source at `794591190fdbaa1a95953218dff827d148755d38`: `plan29-signed-office-79459119/PDFchemy-2.1.0-code14-79459119-signed.apk`, **97,479,090 bytes**, SHA-256 `fb830dd833b9da063b78e9fd208842009682f4febaa038eec50932a602424df6`. Mapping SHA-256 `6f0093acd8199c6d537c2974e96c4934e17c6fe9698cbd8db68f2c1a3d20ec53`. Existing certificate `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b`; signature, 16 KiB zip alignment, non-debuggable status, minification, artifact hygiene and release DEX/Gson gate verified. Main-source hashes are archived for final-head comparison; C05 changes test code/report only. Signed PPTX hashes: mixed `639302a416ab0333f8f28f9c26b9a5ba5e50bc32cf935e911208940ed04510be`, blank `72528e604b477440f04e6815010380a0e83ff7de85b836200cdfa1d0d92e28fb`.

Failed attempts retained: first debug run overlapped release R8 work and had two 15-second binding/setup timeouts (10/12 passed); the sequential rerun passed unchanged timeouts. The full release test APK could not shrink unused MockK/JVM-agent dependencies. A focused test-only classpath built but its instrumented runner crashed before tests because androidx.tracing.Trace was absent after target shrinking. **Signed instrumentation remains unverified**; production keep rules were not weakened. Used the actual signed app UI instead. Several UI automation attempts needed corrected viewport/directory-header/task-reset handling; all restored the original APK and are preserved separately. No failed attempt is counted as a pass.

Changed files: `app-host/src/androidTest/java/com/pdfchemy/app/logic/PowerPointIntegrityTest.kt` and this report. No worker rewrite, Gemini-owned UI edit or production dependency/shrinker-rule change. Physical-device, Play, cross-branch integration and the remaining full signed-runtime gates stay pending.

## C06 - Final reproducible acceptance

Fresh local verification on the completed implementation passed: **505 JVM tests** (312 app-host, 106 pdf-jail, 87 pdf-ipc), zero failures/errors/skips. All three test tasks used per-task `--rerun`; the three external parity tests are separate evidence and are not counted in this total. SecurityAudit/securityArchitecture, dependency inventory, release lint/build and artifact hygiene/DEX/Gson checks passed. Release lint: **0 errors, 642 warnings**. Strict TruffleHog 3.97.9: **6 exact reviewed historical matches, 0 rejected**; no baseline relaxation. OSV: **234 resolved production dependencies, 0 affected packages**. Checker self-tests: **20 passed** (10 secrets, 5 instrumentation runner, 5 release Gson). Local full Gradle run: 9m 28s. XML snapshots/logs are under the shared validation directory with `plan29-c06` prefixes.

Commands, from this worktree (JDK21; two Gradle workers):

```powershell
.\\gradlew.bat :app-host:securityAudit securityArchitecture dependencyInventory :pdf-ipc:testDebugUnitTest --rerun :pdf-jail:testDebugUnitTest --rerun :app-host:testDebugUnitTest --rerun :app-host:lintRelease :app-host:assembleRelease -PunsignedReleaseVerification=true --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process
python -B tools/check_secrets.py
python -B tools/check_dependencies.py
python -B scripts/check_release_gate.py --artifact
python -B -m unittest discover -s tools -p test_check_secrets.py
python -B -m unittest discover -s scripts -p test_run_android_instrumentation.py
python -B -m unittest discover -s scripts -p test_release_gson_contract.py
```

The local secrets diagnostic wrapper delegates to the unchanged strict checker and only redacts/classifies verification errors; this run passed on the first attempt. Dependency checker takes an optional inventory path, not a `--help` flag; a failed help invocation is retained separately from the successful real scan. Final whole-branch diff checking found extra EOF blank lines in four new files that earlier working-tree-only checks missed while they were untracked. Removed those formatting errors in C06; superseded CI run 40875395751 was cancelled and the corrected final SHA receives a new complete run.

**Final same-commit CI acceptance is recorded in PR #14 after this report commit is pushed.** The workflow is explicitly dispatched because the stacked PR base does not match its automatic trigger branches. Acceptance requires all nine jobs on the exact final SHA, downloaded native artifact SHA-256/source binding checks, actual API24/30/34/36 counts, zero failed/errored/skipped cases, and explicit identities for C01-C05 plus existing privacy/geometry/storage/Reader/EPUB regressions. No prior SHA's green run substitutes for this check. Keeping the final SHA/CI receipt in PR metadata avoids changing the tested source merely to insert its own SHA. The shared validation directory retains machine-readable receipts, logs, original failure runs and signed/unsigned artifact hashes.

Focused task commits:

| Task | Commit |
| --- | --- |
| C01 | `dd45e2a24094248dc7c5d06c1d91c34ba7247122` |
| C02 | `02536a2aa798287e5a8610cc1bb6ad8a4102b341` |
| C03 | `2a790c6ff4af0b6687db9e1925821481b11c0b6b` |
| C04 | `794591190fdbaa1a95953218dff827d148755d38` |
| C05 | `b8ca3c8b575126440179c189bff3f5bb657f965b` |
| C06 | This report commit; exact SHA in the final PR receipt. |

Remaining boundaries: signed instrumentation's runner/classpath is unverified; the signed/minified public UI success path and independently parsed outputs are verified as described above. Full signed runtime/security/physical/Play owner gates remain pending. Cross-branch integration must reconcile the pinned CSV parser contract, Settings API wiring and localized Deskew/Office wording, then test the actual integrated SHA. No current Gemini-branch readiness is asserted. SAF cannot guarantee arbitrary-provider interruption/rollback/deletion; unproven legacy shares stay protected and open-descriptor accounting does not enumerate future URI consumers. No main/integration merge, release tag, deployment, Play action or signing-key change.

## Exact changed files since baseline 4aa857af

17 files, including documentation. Per-task changes are identified above; no Billing/Ads/UMP/MainActivity/MainViewModel/ToolSearch/OfficeExportScreen/localized resource/table-worker file was changed.

- [CODEX_PLAN_ACCEPTANCE.md](CODEX_PLAN_ACCEPTANCE.md)
- [app-host/src/androidTest/java/com/pdfchemy/app/logic/PowerPointIntegrityTest.kt](app-host/src/androidTest/java/com/pdfchemy/app/logic/PowerPointIntegrityTest.kt)
- [app-host/src/androidTest/java/com/pdfchemy/app/logic/RetainedShareManagementTest.kt](app-host/src/androidTest/java/com/pdfchemy/app/logic/RetainedShareManagementTest.kt)
- [app-host/src/androidTest/java/com/pdfchemy/app/security/BatchOutputProvider.java](app-host/src/androidTest/java/com/pdfchemy/app/security/BatchOutputProvider.java)
- [app-host/src/androidTest/java/com/pdfchemy/app/security/CsvPublicationBoundaryTest.kt](app-host/src/androidTest/java/com/pdfchemy/app/security/CsvPublicationBoundaryTest.kt)
- [app-host/src/androidTest/java/com/pdfchemy/app/security/ShareRecipientProvider.java](app-host/src/androidTest/java/com/pdfchemy/app/security/ShareRecipientProvider.java)
- [app-host/src/androidTest/java/com/pdfchemy/app/ui/DeskewPreviewIntegrityTest.kt](app-host/src/androidTest/java/com/pdfchemy/app/ui/DeskewPreviewIntegrityTest.kt)
- [app-host/src/androidTest/java/com/pdfchemy/app/ui/PreferredCopyCleanupIntegrityTest.kt](app-host/src/androidTest/java/com/pdfchemy/app/ui/PreferredCopyCleanupIntegrityTest.kt)
- [app-host/src/androidTest/java/com/pdfchemy/app/ui/PreferredCopyPublicationTest.kt](app-host/src/androidTest/java/com/pdfchemy/app/ui/PreferredCopyPublicationTest.kt)
- [app-host/src/main/java/com/pdfchemy/app/logic/PdfGateway.kt](app-host/src/main/java/com/pdfchemy/app/logic/PdfGateway.kt)
- [app-host/src/main/java/com/pdfchemy/app/logic/RetainedShareStorage.kt](app-host/src/main/java/com/pdfchemy/app/logic/RetainedShareStorage.kt)
- [app-host/src/main/java/com/pdfchemy/app/ui/PreferredDocumentCreator.kt](app-host/src/main/java/com/pdfchemy/app/ui/PreferredDocumentCreator.kt)
- [pdf-ipc/src/main/java/com/pdfchemy/app/jail/HostOutputTransaction.kt](pdf-ipc/src/main/java/com/pdfchemy/app/jail/HostOutputTransaction.kt)
- [pdf-ipc/src/main/java/com/pdfchemy/app/jail/WorkerResponseValidator.kt](pdf-ipc/src/main/java/com/pdfchemy/app/jail/WorkerResponseValidator.kt)
- [pdf-ipc/src/test/java/com/pdfchemy/app/jail/CsvBoundaryValidationTest.kt](pdf-ipc/src/test/java/com/pdfchemy/app/jail/CsvBoundaryValidationTest.kt)
- [reports/CSV_BOUNDARY_CONTRACT.md](reports/CSV_BOUNDARY_CONTRACT.md)
- [reports/RETAINED_SHARE_API.md](reports/RETAINED_SHARE_API.md)
