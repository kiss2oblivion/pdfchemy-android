# PDFchemy 2.1.0 modernization closure

This supersedes the earlier claim of unconditional completion/deployment readiness.
Detailed corrections and evidence boundaries are in PDFCHEMY_211_DEPRECATION_FINAL_REPORT.md.

- [x] Preserve and independently replay Gemini's committed modernization work.
- [x] Correct Action runtime pins with a failing/accepted regression gate.
- [x] Update the supported Ads and UMP SDKs and verify focused behavior.
- [x] Migrate deprecated encrypted entitlement storage and test real Keystore compatibility.
- [x] Replace supported notification/parcel compatibility APIs and narrow suppressions.
- [ ] Complete final-source JVM/security/dependency/release validation.
- [ ] Complete final-source API 24/30/34/36 CI and bind artifacts to source.
- [ ] Merge the verified source to main, preserving commit history.
- [ ] Archive obsolete branch identities and remove obsolete refs without deleting checkout work.

Final verification/merge/cleanup receipts belong in PR #17 metadata and the external
validation directory so inserting a receipt does not change its own tested source SHA.
Unchecked items are not claims of failure or success. Play publication, tagging and
signing-identity changes are outside this source-integration authorization.
