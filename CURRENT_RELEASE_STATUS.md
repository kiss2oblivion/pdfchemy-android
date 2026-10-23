# Android 2.1.0 release status

Updated 2026-10-23. Decision: **HOLD**. Package `com.pdfchemy.app`, version `2.1.0`, version code `14`, min SDK `24`, target SDK `36`.

This document is the current summary. Older audit and execution receipts retain dated checkpoints; their former pending/unimplemented statements are historical. Final source and artifact identities must come from the final candidate's build and verification receipt.

## Source and verification

| Checkpoint | Source | Verified scope |
|---|---|---|
| Cross-UID admission, redaction count/page preflight, staging quota retention and fixture manifest repair | `281319da29916d8bb1a473478035d5e13c731fcb` | [CI 39982361631](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39982361631): all nine jobs pass; 270 native methods on each API24/30/34/36, 1,080 executions |
| Form payload/traversal correction | `c52233141b09607255dcc2b86d3401ae35fe045b` | [CI 40003029379](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40003029379): all nine jobs pass; 274 native methods per API, 1,096 executions, including four actual Editor/Search `/V` and `/T` export regressions |
| Eight retained form-value and compatibility cases | `3be961ca9da9f74ca8eac7a2f64210d91f95925b` | Local 53 focused and 94 worker tests pass; zero worker lint errors. [Receipt](reports/security-remediation-2026-10-18/FORM_VALUE_COVERAGE_EXECUTION.md) |
| Release localization | `c973ff64988a2c2e312dbb888b32e77577f82870` | 725 English placeholders replaced across 20 resource sets; 38 messages/760 locale-key pairs; two compiled-resource tests and all 264 host tests pass; release lint zero errors/640 warnings. [Receipt](reports/security-remediation-2026-10-18/LOCALIZATION_EXECUTION.md) |
| Context-wrapper task ownership | `102e9baf2fa0b3c626d8888d60e983094fbf10fd` | Four baseline failures corrected; all 16 context/action combinations, five internal-open controls and all 268 host tests pass; release lint zero errors/640 warnings. [Receipt](reports/security-remediation-2026-10-18/CONTEXT_LAUNCH_EXECUTION.md) |

The final branch includes all changes above. Its complete same-source CI, signed build and artifact checks must be recorded separately; an older green checkpoint does not verify newer production or resource changes. Full local host results include the new resource and context tests; the worker and IPC source are unchanged by those two batches.

SC-01 through SC-14 and the retained RA-05 implementation are present. FOLLOW-01/02 search and picker corrections, external admission, count semantics and form cleanup are implemented. Startup test-driver repair is distinct from proving the original timeout's root cause or physical startup reliability. Retained pre-method ANRs remain separate observations.

## Signing and external gates

The old signed archive identified by `74bb6a82` predates the latest form and context corrections and the localized resources. It is a historical candidate and must not be uploaded as the final build. Rebuild the signed APK, AAB and R8 mapping from the clean final source and retain their full source SHA, hashes, packaged metadata, signer checks, bundle validation, artifact hygiene and 16-KiB compatibility results.

The expected local upload-certificate SHA-256 is `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b`. This does not establish its registration in Play Console. The registered upload-key fingerprint and highest version code already used in Play are still unverified.

| Gate | Current disposition |
|---|---|
| FINAL-01..05 | Final-candidate architecture, dependency/history-secret, complete unit/native matrix, build/lint and artifact checks must match final source; preceding verified checkpoints are listed above |
| FINAL-06 | New signed final-source artifacts required; Play upload-key registration and code availability require external evidence |
| FINAL-07 | Signed physical workflows, 14-fixture corpus/provenance coverage, 15 audio scenarios, screenshot/task-snapshot checks: not certified |
| FINAL-08 | Forensic review of final real export outputs: not certified by intermediate debug exports alone |
| FINAL-09 | Real Play Billing/UMP sandbox, privacy options and revoke checks: not certified by mock/offline tests |
| FINAL-10 | Physical accessibility, Reader/editor latency and memory profiling, printer/foldable/protected-device checks: pending |
| FINAL-11 | Play declaration/video and Data Safety/privacy disclosure reconciliation: pending external submission/review |
| FINAL-12 | Exact final receipt and attributable owner release decision: pending |
| SR-01 / FOLLOW-07 | Owner disposition or stronger verified storage enforcement remains open; see [corrected decision record](reports/security-remediation-2026-10-18/FOLLOW07_EXECUTION.md) |

No risk acceptance, Play upload or release approval is inferred from generated documentation. Final artifacts can be prepared and verified locally while these external gates remain open. The existing checkout and historical evidence are preserved.
