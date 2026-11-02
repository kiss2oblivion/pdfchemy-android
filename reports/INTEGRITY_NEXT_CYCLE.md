# Next-cycle PDF fidelity investigations

Read-only source review on the PR #13 baseline, `2c188f6c5408a3b3a0bedf4c1ebe6ac024f36412`. These are recommendations, not completed runtime verification or hotfix rewrites.

## Find & Replace

`pdf-jail/.../engines/PdfFindAndReplaceEngine.kt`, `replaceAll`, rasterizes every matched page, creates a lossy JPEG, overwrites page content, removes annotations, sets rotation to zero, clears CropBox and replaces MediaBox with renderer dimensions. It retains the existing document graph while writing an optional transparent WinAnsi replacement-text layer; unsupported characters are transliterated or omitted by `sanitizeForWinAnsi`. Unaffected text on a matched page loses its original selectable text structure. Rendering failure aborts rather than falling back to a visual mask.

Next cycle: explicitly define whether this is content editing or forensic replacement; preserve original boxes/offsets/rotation using the Editor geometry approach; test rotated/cropped pages, vector and text fidelity, annotations, Unicode, replacement overflow, searchable unaffected text and decoded object/stream privacy. A page image overwrite is not evidence that all private reachable aliases/resources were removed. Do not advertise forensic erasure without the same graph-level tests as Editor redaction.

## Repair recovery fidelity

`pdf-jail/.../engines/PdfRepairEngineWorker.kt`, `repair`, searches only the first 64 KiB for a header, strips preceding bytes, appends EOF if missing and loads/saves through PDFBox. `recoveredPages` is simply the loaded document's page count. It does not measure recovery against an independently known original or verify page contents, annotations, forms, attachments, outlines or signatures. Rewriting changes byte offsets and invalidates existing digital signatures; a successfully parsed rewrite does not establish complete recovery. Header/EOF substring checks in `diagnose` are preliminary observations.

Next cycle: construct damaged-xref, incremental-update, object-stream, truncated-tail and partially recoverable fixtures with known originals. Compare page geometry, renderings, extracted text and reachable resources; distinguish rewritten, partially recovered, unrecoverable and unverifiable results. Warn explicitly about signature invalidation and partial recovery. Inspect cancellation propagation in host `Result` wrappers. Keep original-safe host publication and bounded worker storage.
