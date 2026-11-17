# Android integration, 2026-11-17

## Scope and source

- [x] Preserve Codex fixes from `b133589f64a531cb4d3298bef93b38fea9eb34dd`.
- [x] Merge Gemini reconciliation `8f5f592eda23ad998a0ea4509faa050c01168f13` and its staged corrections.
- [x] Merge later Gemini commits through `2ce2e3e5fe5b4745187d7249c19264c3e11c25b1`, tracked working changes, and nine source tests.
- [x] Snapshot both original working trees and verify their source hashes remain unchanged.
- [x] Resolve renderer, font export, layout, annotation, and IPC conflicts while retaining Codex attachment identity and staged-input leases.
- [x] Preserve Gemini localization, reader preferences, shapes/forms, font export, EPUB, signing, audio M4A and pipe cancellation, billing recovery, OCR provenance, renderer cleanup, and text-input bounds.
- [x] Fix attachment removal to use the exact attachment ID through the UI and worker.
- [x] Transfer the closeable encrypted multi-picker outcome to the navigation destination; release on rejected navigation or destination completion.
- [x] Adapt audio ownership regression coverage to the claimed-destination API without removing stale-provenance assertions.

## Verification

- [x] Host/IPC/worker unit suites: 628 + 126 + 155 = 909 tests, zero failures/errors/skips; debug and test APK builds passed.
- [x] API 24 affected-device run: 103/104 passed; the speech Retry test assumed an absolute engine count despite the staged-document session replacement. Corrected the test to require one additional engine after Retry. The final speech/picker/layout rerun passed 7/7 with no skips and an empty crash log. Production code was unchanged between these runs.
- [x] The picker regression covers source-screen disposal, eviction pressure, and destination cleanup. Layout checks cover rotated crop geometry, vector text, and rejection of annotations in resize and N-up.
- [x] Unsigned release build, security audit, architecture rules, and artifact checks passed. App release lint passed with three warnings (one plural candidate and two unused resources); explicit IPC/worker/renderer lint reported no issues. Artifact checks passed 14 corpus fixtures, 62 routes, 17 wire models, and six collection signatures.
- [x] Release Gson checker: 16 tests passed.
- [x] Native OCR artifact and current upstream Git advisory check passed; coverage limitations remain documented by the checker.
- Publication is verified against the remote `main` ref after committing; CI results must match that published SHA.

Local logs and original-source snapshots are under `.integration/` in the isolated integration checkout. The original checkouts are preserved. Full-history secret scanning and the complete API 24/30/34/36 matrix must be evaluated for the published SHA in CI. This receipt records source integration, not store-release certification.
