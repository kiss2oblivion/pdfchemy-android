# Security remediation execution — 2026-10-18

Release: **HOLD**. Implementation branch: `codex/security-remediation-2026-10-18`.

## Baseline preparation

- Local and remote implementation baseline match `670578355c4e408c2468ba1564b0e1a366faaa0c`.
- Android remote main: `4b3c157f3458f7df00ea94044258d33c7449ced9`.
- PR #2 remains open/draft on the prior implementation branch. Its baseline checks were all successful in run `38270216907`; this is historical baseline evidence, not verification of the new fixes.
- Review checkout, original implementation checkout and their untracked files are preserved. Production changes use a separate worktree.
- Baseline probes, synthetic leaking PDFs, XML and logs are archived under `reports/security-code-analysis-2026-10-18/`; `evidence/BASELINE_SHA256SUMS.txt` records their original review-relative identities. Probe source copies are under `baseline-probes/`.

## Batch status

| Finding | Implementation | Local verification | Android / CI | Commit / push |
|---|---|---|---|---|
| SC-01 | In progress: fresh redacted-page resources; unused shared/inherited resource pruning | Three resource regressions pass; broader checks running | Actual editor-export regressions added; execution pending | Pending required checks |
| SC-02 through SC-09 | Not started | Not run | Not run | None |
| Retained RA / external URI / SR-01 | Not started | Not run | Not run | None |

## SC-01 evidence and limits

The first focused JVM invocation failed because PDFBox font/glyph assets were unavailable in the worker module's test environment. Failure log/XML are retained in `sc01/`. Enabling Android resources for worker unit tests resolved that fixture problem; all three focused resource tests then passed.

The component regressions verify original Form removal, original image-object removal, and inherited/shared unused-resource removal while retaining unrelated vector text. Android tests invoke the actual Editor → gateway → isolated worker export, inspect saved decoded objects, retain unrelated text, and check opaque output pixels. Adding/compiling a test is not execution evidence.

No later finding is advanced before the required SC-01 checks and commit/push/CI evidence. This receipt will be updated with actual outcomes rather than inferred completion.
