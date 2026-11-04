# Billing recovery acceptance

Baseline: main 7fd999b627ce01daa925ead5844b35e0ff6e633e.
Branch: fix/2.1.0-billing-recovery. No merge or release authorization is implied.

## B01 - Foreground purchase reconciliation

Original reproduction: four Robolectric activity-lifecycle tests failed on the unchanged manager. No activity callback queried inventory or reconnected after foreground return; the manager also lacked owner-bound connection cleanup. Evidence: external billing-recovery-evidence/b01-original (4 tests, 4 failures, 0 errors/skips).

Correction: register application activity-resume callbacks inside BillingManager, coalesce callbacks through its owner scope, wait for an existing inventory/restore before refreshing, reconnect through the existing connection guard, and unregister/close/invalidate callbacks when that scope completes. No Activity is retained. Existing request-generation checks and cached entitlement on Play errors remain intact.

Verification: b01-fixed, app-host JVM billing package, 63 tests passed with 0 failures/errors/skips, including the four new foreground, overlapping-restore/recreation, reconnect/error and disposal cases. Debug compilation and pre-build security/architecture checks passed. No real Play purchase, physical device or signed runtime claim. Final lint, full JVM/native/CI and signed artifact verification remain B06.

Changed files: BillingManager.kt, BillingForegroundRecoveryTest.kt, this report. Commit identity is recorded externally after commit to avoid modifying the tested source to insert its own SHA.

## Remaining planned work

- B02: acknowledgement recovery beyond immediate retries.
- B03: bounded restore and operation-specific retry.
- B04: entitlement persistence failure handling.
- B05: ITEM_ALREADY_OWNED reconciliation.
- B06: complete final-source validation and replacement signed artifacts; owner Play/device gates separately recorded.
