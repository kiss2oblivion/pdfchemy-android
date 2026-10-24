# Preserve cancellation during staging read closure

Base: `04efef6c7930a45f3324cbd67b5e9e33c059597e`. Date: 2026-10-25.

Full CI [40130949345](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40130949345) exposed one failure among 271 host unit tests: StagingSynchronizationSecurityTest.sc07_04_cancellationOfStalledStaging_reclaimsReservationsAndDeletesPartialFiles. The read was unblocked by cancellation closing its stream, which threw IOException. The cancellable staging helpers rethrew that I/O exception, allowing a cancelled child to fail its parent. The failed run and logs are retained; it is not a passing candidate.

Both leased and unleased helpers now finish their existing cleanup and, only for IOException, check whether the owning job was cancelled. A cancelled job propagates its cancellation cause; an active job retains its genuine I/O failure. Security exceptions and other failures are unchanged. This uses the existing [Job.ensureActive contract](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html). No quota, publication or isolation check is removed.

The new stream fixture ignores interruption until the cancellation closer signals it, then deterministically throws IOException. Two baseline tests reproduce the parent failure; the active-I/O control passes. The same three tests all pass after the fix. They require reclaimed slot/byte reservations, deleted partial snapshots, no staged document, no pending transfer resource and a live parent after child cancellation. The active-I/O control requires the original exception type and message for both staging paths.

The initial probe used exception object identity for the active-I/O control. Coroutine stacktrace recovery copies that exception, so the assertion was corrected to the actual type/message contract before collecting the controlled baseline. The initial three-failure probe and the corrected two-failure baseline are both preserved.

## Local verification

- New regressions: 3 pass; existing synchronization class: 5 pass.
- All unit suites pass: pdf-ipc 54, pdf-jail 94, app-host 271.
- securityAudit and securityArchitecture pass.
- Release lint: zero errors, 640 warnings.
- PdfEditorWorker remains byte-for-byte unchanged from the geometry commit.

Raw evidence outside the checkout: ../editor-coordinate-ci-40130949345-unit-failure.log, ../staging-cancellation-baseline-initial.xml, ../staging-cancellation-baseline-controlled.{log,xml}, ../staging-cancellation-focused-fixed-reports/, ../staging-cancellation-focused-fixed.log and ../staging-cancellation-full-validation.log.

## Resulting-source checks

This batch records local verification before its commit. The final resulting source still requires complete API24/30/34/36 CI and fresh signed-minified artifact/export identities. Physical-device and printer checks remain pending at the user's request. Billing, UMP, ads, Play readiness and shared release-status documents are outside this repair.
