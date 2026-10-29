# Codex C01-C06 acceptance

Baseline: `4aa857af6b8ccac2f1fd0cf93ed9761b5bb4389e`, PR #14, stacked on PR #13. Preserve prior fixes; no merge, tag, deployment or Gemini-owned implementation edits. Final commit identities and same-head CI results are recorded in PR #14 after verification, avoiding a self-changing acceptance SHA.

## C01 - Deskew preview request ownership

The implementation already landed in `f7872ff607e79cc385d4a85d2db8327b53860966`: monotonically increasing generations, previous-job cancellation, ResourceTransferOwner handoff, disposal and explicit failure state. The original routine reproduced three failures in four native cases (stale preview, retained preview on failure and disposal leakage); see CODEX_RELEASE_CLOSURE.md. No new production defect was found or speculative rewrite introduced.

Added actual native regressions for A/B/C completing C/A/B (only C remains) and 16 overlapping noncancellable producers with reverse completion. Every rejected bitmap is recycled; the current bitmap stays live until disposal; repeated close is safe. Existing replacement/failure/disposal tests remain.

Verification: `plan29-c01-accepted`, owned offline API36, **6 tests, 0 failures/errors/skips**. Build passed; original installed signed candidate restored and hash checked. The first native attempt stopped before tests because the build used the default debug certificate; an initial signing-helper command had an incorrect init-script path. Both failed attempts remain recorded. Rebuilt using the existing release-signing/debug-certificate helpers without changing keys. Production algorithm/export behavior unchanged.

Changed files: this report and `app-host/src/androidTest/java/com/pdfchemy/app/ui/DeskewPreviewIntegrityTest.kt`. Full four-API/final-source verification belongs to C06; this focused result is debug instrumentation, not a signed/minified runtime claim.
