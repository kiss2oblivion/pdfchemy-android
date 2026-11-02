# CA-02: compare Editor state with its saved baseline

Base: `d26a3acdfb73a77d4f5657b5a184b9e5e41b53e6`. Date: 2026-10-25.

The old dirty-state predicate required at least one current page modification to
have changes. Undoing a saved rotation, removing the last saved annotation, or
restoring a saved deleted page could therefore report clean despite differing
from the saved PDF. Conversely, retaining an empty modification entry on another
page could cause a false dirty warning while a saved page still had changes.

The predicate now compares current and saved modifications after omitting empty
page entries on both sides. History and export behavior remain unchanged.

Changed files in this batch:

- `app-host/src/main/java/com/pdfchemy/app/logic/EditorSession.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/EditorSavedStateRegressionTest.kt`
- `reports/editor-followups-2026-10-25/CA02_EXECUTION.md`

## Regression evidence

The same six new tests ran against the unfixed and fixed implementation. The
baseline had five intended assertion failures and one passing no-op control.
All six pass after the change. The four existing EditorSession tests pass on
both implementations. Tests cover saved rotation undo/redo and reversal, saved
annotation removal, saved deletion restoration, a reverted edit on another page,
and empty entries in both saved/current states.

Raw baseline and focused-fixed logs and XML are preserved outside the checkout:
`../ca02-baseline.log`, `../ca02-baseline-reports/`,
`../ca02-focused-fixed.log`, and `../ca02-focused-fixed-reports/`.

## Verification

- Source review: the production change is limited to the dirty-state predicate.
- Unit tests: all 277 host tests pass, zero failures/errors/skips.
- Security checks: `:app-host:securityAudit` and `securityArchitecture` pass.
- Release lint: zero errors, 640 warnings; debug APK assembly passes.
- `git diff --check`: pass.
- Emulator/instrumentation: no new run for this model-only batch.
- CI and signed/minified artifact: pending on the final followups source;
  the earlier green geometry CI verifies `d26a3acd` only.
- Physical device/printer: pending at the user's instruction.

Full local validation log: `../ca02-validation.log`. This report does not establish
release readiness or close CA-01/SR-01, CA-03 or CA-04.
