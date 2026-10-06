# PDFchemy Android — Audit Handoff

Date: 4 October 2026. Status: stopped for review.

## Outcome

A visual redesign is not warranted. Preserve the current visual language, dashboard concept, category cards, Merge workspace, grouped Settings, Premium placement, skippable four-slide tour, original-safe copy export, and Android storage/security architecture.

The full report records 50 findings: 16 P1, 33 P2, and one P3. Four additional observations are explicitly separated from defects. No P0 was established.

## Highest priorities

1. Editing/session and output correctness: dirty departure, chronological undo/redo, organizer page preservation, annotation coordinates, and mixed-redaction export.
2. Reader quality: essential reading/navigation capabilities, document-first hidden chrome, and clean advertising eligibility.
3. Deterministic tool search across the existing categories, without usage history or behavioral ranking.
4. Current-document Reader-to-tool-to-Reader continuity, stable document identity, and reading preferences.
5. Human recovery, truthful progress, safe cancellation, and consistent output naming/results/save control.

The full report supplies the ranked top ten, cost/risk estimates, evidence, and proposed implementation phases. These are proposals, not executed changes.

## Verified runtime examples

- Compression completed, but the result offers Share/OK rather than Open/Files/Another Operation/Home.
- Merge displays selected files and supports successful arrow reordering; its progress incorrectly says “Compressing PDF...”.
- Reader controls remain visible. Add text → Back → reopen Editor loses the session and returns to selection without a save/discard decision.
- Organizer exposes no Undo/Redo; deletion changes seven pages to six; copy save completes.
- A truncated, script-free PDF is falsely reported as containing embedded scripts. OK returns to selection without cause-specific recovery.
- System Back from Settings exits to the launcher, unlike its toolbar Back.
- At simulated 320dp width/font scale 2.0, identifying category titles truncate.

## Limits

Runtime used an existing API 36 debug APK on a software-rendered emulator. The debug screenshot harness suppresses ads and first-run flows and injects the compression asset. Ads and several conditional export failures are source-audited, not runtime-reproduced. Protected document screenshots remain black because of intentional `FLAG_SECURE`; hierarchy/code evidence was used without bypassing protection.

Physical-device performance, full TalkBack traversal, foldable posture, real billing/ads, memory pressure, broad failure/provider coverage, and safe cancellation still require validation. The report identifies these explicitly.

## Change boundary

Production tracked files and staged changes were checked and unchanged. Only audit documentation/evidence was added. Emulator display, font, and rotation settings were restored. No fix commit, component replacement, refactor, navigation change, or redesign was performed.

Next action: review the audit and authorize a separate implementation scope. Do not treat the proposed phases as permission to begin implementation.

[Full report](PDFCHEMY_ANDROID_FULL_UI_UX_AUDIT.md) · [Evidence guide](EVIDENCE.md) · [Package index](README.md)
