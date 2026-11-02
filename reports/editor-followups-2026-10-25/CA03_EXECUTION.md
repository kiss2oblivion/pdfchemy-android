# CA-03: retain both Editor bitmaps until delivery

Base: `d7591feb3e4f363a10e2556db3d5e93d41260103`. Date: 2026-10-25.

The dual-page wrapper rendered two pages inside `withContext(IO)` without retaining
cleanup ownership outside that dispatcher boundary. If the second render threw
or was cancelled, the completed first bitmap had no consumer and no cleanup.
Prompt cancellation while the completed pair was queued for caller resumption
could also discard both bitmaps.

Each page now has a `ResourceTransferOwner` created outside the IO context. Each
completed allocation is staged immediately. Both resources transfer only after
the caller resumes successfully; closing the owners recycles unclaimed bitmaps
on every exceptional/cancelled exit. Successful results remain caller-owned.

Changed files in this batch:

- `app-host/src/main/java/com/pdfchemy/app/logic/PdfEditor.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/EditorBitmapOwnershipRegressionTest.kt`
- `reports/editor-followups-2026-10-25/CA03_EXECUTION.md`

## Regression evidence

Seven tests invoke the actual dual-page wrapper, mocking only the renderer
boundary and using real Robolectric bitmaps. The same tests reproduce three
cleanup failures on the baseline; all seven pass after the change, with no skips.

The failing cases cover second-render IOException, cancellation during the second
render and cancellation after both renders finish but before caller resumption.
The queued-dispatcher test holds the actual resumption task; it uses no timing
sleep to create the cancellation window. Controls verify successful transfer,
a negative right-page index, first-render failure propagation and a null left
result. Successful bitmap identity and non-recycled state are asserted.

Raw evidence outside the checkout: `../ca03-baseline.{log,xml}` and
`../ca03-focused-fixed.{log,xml}`.

## Verification

- Source review: only dual-page resource ownership changes; renderer isolation,
  UI behavior and output publication are unchanged.
- Focused unit tests: 7 pass, zero failures/errors/skips.
- Full host unit suite: 284 pass, zero failures/errors/skips.
- Security audit and architecture checks: pass.
- Release lint: zero errors, 640 warnings; debug APK assembly passes.
- `git diff --check`: pass.
- Emulator/instrumentation: no new run for this host-side wrapper batch.
- CI and signed/minified artifact: pending on the final followups source.
- Physical device/printer: pending at the user's instruction.

Full local validation log: `../ca03-validation.log`. This report does not close
CA-01/SR-01 or CA-04 and does not establish release readiness.
