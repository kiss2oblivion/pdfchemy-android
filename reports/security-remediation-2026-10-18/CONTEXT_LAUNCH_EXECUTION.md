# Preserve Activity task ownership through context wrappers

Release: HOLD. Base source: `c973ff64988a2c2e312dbb888b32e77577f82870`.

The integrated Share and external View paths checked only whether their immediate Context was an Activity. ContextWrapper and ContextThemeWrapper can delegate to an Activity; adding NEW_TASK in those cases changes the launch task despite an Activity owner. The earlier isolated share correction handled this, but that part had not been integrated.

Four regression methods fail on unchanged production: single-document sharing, multiple-document sharing, single-image sharing and external audio View. Each failure reports expected NEW_TASK=false but actual=true for a nested Activity wrapper. Application, application wrapper and direct Activity controls precede that failure.

A shared internal Context.hasActivityContext helper unwraps context identity with bounded cycle detection. Share and external View now add NEW_TASK only when no underlying Activity is found. URI admission, the private internal-open alias and read grants are unchanged.

## Verification

- Baseline: four tests, four expected task-flag assertion failures; zero test errors.
- Corrected focus: four methods, covering 16 context/action combinations, all pass with read-grant checks. Five internal-open policy controls also pass.
- Complete host suite: 268 tests, zero failures/errors/skips.
- Release lint: zero errors, 640 warnings.

Raw logs and XML are preserved outside the checkout under `release-context-baseline.log`, `release-context-baseline-results/`, `release-context-fixed-focused.log`, `release-context-fixed-focused-results/`, `release-context-full-validation.log` and `release-context-full-results/` in the release-validation directory. Portable source identities are in `evidence/CONTEXT_LAUNCH_RECEIPT.json`.

Commit/push and final resulting-source CI remain pending at this receipt snapshot. These are JVM/compiled-resource checks; final native, signed-device and Play gates remain separate.
