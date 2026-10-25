# CA-04: bound annotation overlay resolution independently of PDF points

Base: `ded31869b424693253fb88bfc817c5c58a76d978`. Date: 2026-10-25.

Annotation-only export requested two pixels per displayed PDF point without
bounding the bitmap. A3's long edge therefore exceeded the existing 2048-pixel
limit; A2 also failed. The worker aborted before host publication.

The annotation branch now uses the existing `SecurityLimits.safeRenderSize`
helper at its default 2x requested scale. The same 2048-dimension and 4,194,304-pixel
limits remain enforced. The existing inverse-rotation image placement continues
to composite the overlay into the original CropBox in PDF points. MediaBox,
CropBox, offsets and original/requested rotation handling are unchanged. The
independent redaction engine is untouched.

Changed files in this batch:

- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfEditorWorker.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/EditorLargePageExportSecurityTest.kt`
- `reports/editor-followups-2026-10-25/CA04_EXECUTION.md`

## Controlled native regression

Eight new tests exercise actual isolated Editor exports: A3/A2 portrait and
landscape, A3 crop offsets with 90-degree rotation, A2 crop offsets with 270-degree
rotation plus a requested quarter-turn, and A4/Letter controls. They reopen the
saved PDFs and assert exact boxes, rotation, retained original vector text,
bounded embedded image dimensions/pixel counts, unchanged source bytes and
rendered registration/annotation alignment.

The final tests reproduce exactly six quota failures on the original production
implementation; A4 and Letter pass. Baseline APKs, signatures, source hashes,
per-method results and logs are preserved in
`../ca04-native-baseline-normalized/`.

The initial fixed-source run passed 18/19 tests. Its one failure came from a new
text assertion: PDFBox inserts line breaks between some glyphs in the rotated
input itself. A separate input-positive-control diagnostic reproduced this before
export. The final test removes whitespace from both extracted strings and
requires complete text equality as well as the full marker. Both baseline and
fixed builds are rerun with that identical final test source. The initial failed
run and diagnostic are retained in `../ca04-native-fixed/` and
`../ca04-native-diagnostic/`. No further production change was made for this
assertion correction. The initial baseline result-parser CRLF failure was
corrected by verifying its saved device log, without rerunning instrumentation.

## Verification

- Native fixed-source tests: all 19 pass on the owned API36 emulator, zero
  failures/skips/crashes. This includes all eight new cases and all eleven
  existing geometry/redaction cases. Raw logs, APKs and a per-method receipt are
  in `../ca04-native-fixed-normalized/`; baseline and fixed receipts bind the
  same final regression-test source hash.
- Unit tests: all 432 pass (54 IPC, 94 worker, 284 host), zero
  failures/errors/skips. XML and counts are preserved in `../ca04-unit-reports/`
  and `../ca04-unit-counts.json`.
- Security audit and architecture checks: pass.
- Release lint: zero errors, 640 warnings. `git diff --check`: pass.
- Debug app/test APK builds: pass; both use the existing certificate for the owned
  emulator, and are explicitly not minified release verification.
- CI and fresh signed/minified export verification: pending on the committed
  followups source. Historical geometry evidence verifies `d26a3acd` only.
- Physical device/printer: pending at the user's instruction.

This batch does not close CA-01/SR-01 or establish deployment readiness.

Full local validation log: `../ca04-validation.log`. Release lint XML is retained
at `../ca04-lint-release.xml`.
