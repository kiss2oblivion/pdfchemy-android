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
