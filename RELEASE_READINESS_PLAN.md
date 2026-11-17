# PDFchemy 2.1.0 release candidate

Code commit: ba65832b56d8443b37d51423330567eff2b63bac. Package/version: com.pdfchemy.app, 2.1.0 (14), SDK 24..36. The owner reports the current public version is 2.0.3; its numeric Play version code has not been supplied.

- [x] RA-01: Localized result-action regression selectors. Large-font, long-result and stale-ad callback assertions pass on native Android.
- [x] RA-02: Find & Replace edits glyph content and affected Form invocations, preserves unrelated text/vectors, page boxes, rotation and annotations, and keeps exact Unicode searchability. Hidden OCR replacements remain visible above scan images. Fifteen worker fidelity regressions and the four added native fidelity cases pass, including multilingual and independent API 36 PDFium search checks.
- [x] RA-03: Cancellation and deferred onCleared regressions wait for actual provider return and cleanup completion. Explicit barriers verify provenance remains claimed while deletion is in progress. All 25 destination-integrity checks pass; production cleanup code is unchanged.
- [x] RA-04: Replacement colors use literal wire keys, avoiding R8-renamed kotlin.Triple fields. The old payload fails the field-renaming regression; the fixed payload and actual signed Find -> Replace -> Save workflow pass.
- [x] Local verification: 925 unit tests, architecture/security audits, explicit host/library lint, debug/test APK builds, signed release APK/AAB and release artifact contracts. Zero test failures/errors/skips and zero lint errors. Host lint retains three inherited warnings (one PluralsCandidate and two UnusedResources); library lint reports no issues.
- [x] Exact-code [CI 44098971190](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/44098971190): all nine jobs pass. Native matrix total: 2247 passing executions, zero failures/errors/skips (api24: 561, api30: 562, api34: 562, api36: 562).
- [x] Signed artifacts: recorded project certificate f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b; minified production package/version; embedded bundletool validation; APK ZIP and 64-bit ELF 16-KiB alignment; fixture/test-asset hygiene and minified Gson/text/YAML contracts.
- [x] Signed public workflows: Find & Replace, merge, split, OCR, EPUB, CBZ, protect, unlock, wrong-password rejection, sanitizer purge and sanitizer keep. Independent saved-output parsing/rendering and source/old-target hash checks pass. All four image-only OCR pages gain searchable text. Wrong-password rejection publishes no output.
- [x] Forensic output checks: replaced word absent from extracted text and decoded streams; pixels outside the edit unchanged; sanitizer purge removes attachment bytes from raw output and every decoded stream; explicitly disabled attachment purge preserves the original attachment bytes. Sanitized page geometry and text remain intact.
- [x] Signed configuration restoration: category and Office screens survive real Activity recreation. Native result checks also cover doubled font scale. Crashes, disconnected attempts and driver navigation failures were excluded from passing receipts.
- [x] Separate release package: D:/PDFchemy-Releases/2.1.0-ba65832b-20261117, with APK, AAB, matching R8 mapping, SHA256SUMS and verification receipts. Original source checkouts and their staged/untracked work remain preserved.

Deployment follow-ups owned by the release owner:

- [ ] Confirm Play accepts numeric version code 14 and the upload certificate is registered.
- [ ] Complete real Play Billing/purchase/restore/acknowledgement and UMP consent/revoke/privacy-option acceptance, plus store declarations review.
- [ ] Test on the owner's phone after deployment, as explicitly requested. Physical ARM64/16-KiB execution is not claimed; a connected phone is not required for this completed code/emulator preparation.

No Play upload or store publication was performed. The documentation closeout contains no production-code changes relative to the verified code commit above.

Evidence: .release-ready/final-local-gate.log, .release-ready/ci-44098971190/, .release-ready/artifacts/, .release-ready/final-signed-runtime.json, and the package verification/ directory. The two replaced CI attempts and the old signed failure baseline remain separate evidence.
