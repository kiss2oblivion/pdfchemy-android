# PDFchemy 2.1.0 release readiness

Target: version name 2.1.0. User reports Play currently lists 2.0.3; its numeric version code remains to be verified. Start from main c405a6d53672c6d2c35c30b88b4e8f450072e513 in an isolated worktree.

- [x] RA-01 (local API 24 and API 36: 4/4 each passed; full CI remains below): Localize result-action test selectors; preserve large-font, long-result and stale-ad callback assertions; verify on native Android.
- [x] RA-02 (15 worker regression tests and 11 native checks on each of API 24 and API 36 pass; full API matrix remains below): Replace destructive whole-page Find & Replace rasterization with content editing that preserves unaffected vectors/text, annotations and page geometry; preserve exact Unicode replacement searchability and remove replaced source text. Cover cropped/rotated pages, split operators, forms, annotations and multilingual output with actual saved PDFs.
- [ ] Re-run final local gate after RA-04. Previous candidate: 924 host/IPC/worker unit tests, architecture, explicit lint for host and three libraries, debug/test and signed release builds, and artifact contracts pass. Host lint retains three non-fatal inherited warnings; library lint reports no issues.
- [ ] Run same-source full native CI on API 24/30/34/36, including the dependent release job.
- [ ] Rebuild and verify final signed artifacts after RA-04. Previous APK/AAB and mapping built from bd2c2771; signer, version/package, embedded bundletool validation, ZIP and 64-bit ELF 16-KiB alignment, artifact hygiene and source-bound hashes verified under .release-ready/artifacts/. Play registration remains a separate unchecked gate.
- [ ] Exercise signed public processing journeys and forensic output checks; preserve original document bytes.
- [ ] Complete available emulator lifecycle, low-memory, accessibility/font-scale and provider-failure checks.
- [ ] Owner follow-up: user explicitly plans phone testing after deployment. Physical ARM64/16-KiB execution is not claimed; a connected phone is not required for the current source/emulator work.
- [ ] Verify real Play Billing/restore/acknowledgement and UMP consent/revoke/privacy options and protected-screen/Premium exclusions with the actual candidate.
- [ ] Verify Play upload certificate registration, highest used numeric version code, and Data Safety/declarations.
- [ ] Produce an exact-source final receipt and release verdict. Unverified gates stay open; no store publication is implied by local verification.

Evidence and temporary tooling: .release-ready/ on D:. Original worktrees and evidence remain untouched.

RA-02 changes: edit encoded glyph operators and clone only affected Form invocations; retain surrounding text, vectors, boxes, rotation and annotations. Preserve exact replacement Unicode with embedded text or native shaping plus a Unicode search layer. Hidden OCR replacements are drawn above scan images. Search failures produce a localized error, and stale search responses are ignored. The localized notice distinguishes editing from secure redaction and warns about digital signatures.

Focused evidence: .release-ready/ra02-unit-ocr.log, .release-ready/ra02-api24-with-ocr/. The complete final test APK passes 11/11 on both API 24 and API 36; the API 36 retry supersedes its earlier disconnected attempt. Old-engine regression evidence is retained separately under .release-ready/baseline/.

- [x] RA-03: Make the cancellation regression wait for provider deletion and teardown completion, rather than treating callback entry as completed cleanup. The focused destination-integrity suite passes, including an explicit barrier proving provenance remains claimed while the provider delete call is still open. Production code is unchanged.

CI 44093554160 on bd2c2771 passed architecture, secrets and dependency checks, but exposed the RA-03 test timing race in the host unit job. It is not a green release receipt; a new complete CI run must pass with the corrected regression. Signed public merge completed via the actual picker/SAF save path with original and previous destination bytes preserved. The initial public split attempt lost the shared ADB connection and is not a pass; subsequent public checks use a private ADB server.

The follow-up CI 44096328311 exposed the same callback-entry race in deferred onCleared cleanup. Both cancellation paths now explicitly hold the provider open, assert retained provenance, release the provider, and wait for cleanup completion. All 25 destination-integrity tests pass. The superseded run was cancelled after its host unit failure; it is not release approval.

- [ ] RA-04 signed verification: Find & Replace RGB channels now use literal JSON keys rather than reflected kotlin.Triple fields. The old signed APK failed the public replacement route; its mapping renamed first/second/third to c/d/q while the worker required the original names. The new wire regression fails against the old payload and passes with the explicit map; all 26 focused wire/cleanup checks pass. Rebuild the signed candidate and repeat public search/replace/save plus independent output validation.
