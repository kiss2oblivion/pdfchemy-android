# Audio publication cancellation on API 24

Status: implemented; final local build/unit/security checks and API 24/36
regressions pass. Matching-source CI, including committed-HEAD secrets, remains
a delivery gate until its actual result is recorded. Base: `2cd8ab944bf7a3dd9a093582c0ff3d2043229a73`.

## Reproduction

A real SAF-style provider holds its pipe reader behind a release gate. A 2 MB
WAV publisher writes into that pipe; the fixture observes kernel-buffered bytes
before cancellation. On unchanged production source, API 36 passed, but API 24
failed the two-second cancellation-join assertion. The test releases the reader
in `finally`, allowing the old blocked write and cleanup to finish safely.
This is a native platform reproduction, not an OutputStream JVM mock.

The first API 24 attempt lost its ADB connection and has no valid result. The
subsequent valid retry reproduces the failure. Evidence:
`pipe-baseline-api36.log`, `pipe-baseline-api24.log` (incomplete),
`pipe-baseline-api24-retry.log`, and `.pipe-evidence/emulator-*-baseline*/`.

## Implementation

- Extract the existing PDF pipe writer into `CancellableProviderWriter` in
  `pdf-ipc`, and use it for PDF transactions and audio publication. FIFO writes
  use `O_NONBLOCK`, preserve partial-write offsets, handle `EAGAIN`/`EINTR`, and
  poll with a 50 ms timeout while checking cancellation between attempts.
- Check cancellation before descriptor inspection, including after a canceled
  provider open returns. Probe exceptions also recheck cancellation so a closed
  descriptor does not replace the cancellation result with an unrelated error.
- Preserve cancellation when a canceled provider open returns null or throws
  another exception. A JVM regression reproduced the former misclassification
  before this fix; both null-return and exception variants now pass.
- Audio opens a whole-file descriptor and wraps it in an owning output stream;
  this rejects bounded asset slices before raw pipe writes could bypass their
  range accounting. Constructor failure closes the newly acquired descriptor.
- Keep stream closure, writer leases, publication receipts and destination
  ownership with their existing callers. Uninterruptible provider calls retain
  their writer protection until they return and cleanup can actually finish.
- Add stalled-reader cancellation and resumed-reader complete-delivery device
  regressions. The resumed case checks both byte count and SHA-256 for the full
  WAV, plus exactly one publication receipt. Its fixture sends a small digest
  response rather than putting a 2 MB result into a Binder transaction.

The JVM fixtures mock only their native-open boundary or known regular-file
metadata. Robolectric 4.11.1 loses file type in `fstat(fd)` by calling
`stat(null)`. A custom Linux shadow was rejected after the full suite exposed
cached-shadow cast failures; the final fixture uses a scoped `Os.fstat` stub
and clears it after each test. A fixture test verifies regular-file detection.
Real pipes and descriptor behavior remain covered by instrumentation.

## Verification

- Final application checks pass: 579 host, 115 IPC and 136 worker unit results
  (830 total), zero failures/errors/skips, debug and instrumentation APK builds,
  security audit and architecture. Release lint has zero errors and 622 warnings.
  `.verification/pipe-final.log` records the successful 9m 31s run.
- API 24: all 40 selected audio/PDF/text publication methods pass, including the
  previously failing stalled-reader assertion, resumed full-WAV delivery,
  failed/partial opens, immutable destinations, cancellation, cleanup ownership,
  worker result/snapshot validation, multi-output commits and PDF publication.
  Strict final runner output:
  `.pipe-evidence/emulator-5582-final/instrumentation.log` (133.316 seconds).
- API 36: the same 40 methods pass on the final APKs (150.611 seconds).
  `.pipe-evidence/emulator-5580-final/instrumentation.log` records 40 successful
  method completions, `OK (40 tests)` and final instrumentation code -1.
- App APK SHA-256:
  `11385665fed367c1cdd911b60a271019200556525c6c2ab106681cefc07f775d`.
  Test APK SHA-256:
  `f838bd6e10bd136b7bcb1f0a477e489f164d8ed32c0f96d517f88b426a6344f7`.

The preceding native OCR commit passes all nine jobs in CI 42916774252,
including 536 methods on each API 24/30/34/36. This pipe batch requires its own
matching-source CI. Physical devices, signing, Play/Billing/UMP and other release
gates remain separate.
