# PDFchemy 2.1.0 release readiness

Target: version name 2.1.0. User reports Play currently lists 2.0.3; its numeric version code remains to be verified. Start from main c405a6d53672c6d2c35c30b88b4e8f450072e513 in an isolated worktree.

- [x] RA-01 (local API 24: 4/4 passed; full CI remains below): Localize result-action test selectors; preserve large-font, long-result and stale-ad callback assertions; verify on native Android.
- [ ] RA-02: Replace destructive whole-page Find & Replace rasterization with content editing that preserves unaffected vectors/text, annotations and page geometry; preserve exact Unicode replacement searchability and remove replaced source text. Cover cropped/rotated pages, split operators, forms, annotations and multilingual output with actual saved PDFs.
- [ ] Run complete host/IPC/worker units, architecture, explicit lint, debug/test and release builds, and artifact contracts.
- [ ] Run same-source full native CI on API 24/30/34/36, including the dependent release job.
- [ ] Build final signed APK/AAB and mapping; verify signer, version/package, bundle structure, alignment and artifact hygiene; bind hashes to source SHA.
- [ ] Exercise signed public processing journeys and forensic output checks; preserve original document bytes.
- [ ] Complete available emulator lifecycle, low-memory, accessibility/font-scale and provider-failure checks.
- [ ] Verify physical ARM64/16-KiB behavior or record the explicit outstanding gate; currently connected devices are TVs and user offers an Android Studio emulator.
- [ ] Verify real Play Billing/restore/acknowledgement and UMP consent/revoke/privacy options and protected-screen/Premium exclusions with the actual candidate.
- [ ] Verify Play upload certificate registration, highest used numeric version code, and Data Safety/declarations.
- [ ] Produce an exact-source final receipt and release verdict. Unverified gates stay open; no store publication is implied by local verification.

Evidence and temporary tooling: .release-ready/ on D:. Original worktrees and evidence remain untouched.
