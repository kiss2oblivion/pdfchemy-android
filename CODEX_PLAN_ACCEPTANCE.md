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
