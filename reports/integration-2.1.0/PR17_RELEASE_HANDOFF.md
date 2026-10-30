# PDFchemy 2.1.0 owner handoff

**Status: HOLD for release.** Earlier READY/GO recommendations and artifact hashes are historical source-specific evidence, superseded by this record. No main merge, tag, deployment, signing-identity change or Play action is authorized.

Canonical: `integration/2.1.0-readiness-2026-10-29`, draft [PR #17](https://github.com/kiss2oblivion/pdfchemy-android/pull/17). Codex owns implementation, regression tests, integration and release engineering. Gemini owns the independent full-product security audit under [the canonical owner directive](https://github.com/kiss2oblivion/pdfchemy-android/pull/17#issuecomment-6522746064).

The [current correction report](PR17_CLOSURE_2026_10_30.md) records both reproduced failures, exact changed files and before/after results:

- `84b883fb661ccda099964c781e6445947d5c06b4`: normalize provider-close IOException to cancellation only when the audio coroutine is canceled. Replace the blanket ignored-IOException test catch with cancellation/uncaught-error assertions. Original full host suite: 425/1; corrected wider suite: 648 JVM tests, zero failures/errors/skips.
- `e3f22dd19919e525bcbecd42bb83541234df91af`: clearing/disabling history no longer deletes retained recipient copies. Add explicit Settings management with confirmation, quotas, active-reader protection and partial-result reporting. Original focused JVM: 3/2; fixed focused JVM: 26/0; actual API36 native: 18/0, original installed signed APK restored and hash checked.

Final report-bearing SHA, exact-head nine-job CI, per-API test counts, signed/minified APK and signed AAB paths/hashes, signature/alignment checks and public-runtime receipts will be attached to PR #17 after verification. None are closed merely by this pre-run report. The prior ddb APK/AAB predates these production corrections and is not the final candidate.

Version remains 2.1.0/code14, target SDK36. Existing signing certificate SHA-256 is `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b`; key identity is unchanged. Local signing does not establish Play's registered upload certificate or version-code availability. Static ELF/ZIP alignment is not 16 KiB device runtime evidence.

Owner-controlled gates remain open: physical signed ARM64/16 KiB checks (previously left pending), Play upload certificate/version eligibility, store-account Billing/UMP and policy approval. Final signed forensic redaction and all unexecuted signed public journeys remain separately tracked in [the checklist](../../RELEASE_GATE_CHECKLIST_2_1_0.md). Passing emulator CI is not Play approval.
