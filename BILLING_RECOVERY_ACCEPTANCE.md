# Billing recovery acceptance

Baseline: main 7fd999b627ce01daa925ead5844b35e0ff6e633e.
Branch: fix/2.1.0-billing-recovery. No merge or release authorization is implied.

## B01 - Foreground purchase reconciliation

Original reproduction: four Robolectric activity-lifecycle tests failed on the unchanged manager. No activity callback queried inventory or reconnected after foreground return; the manager also lacked owner-bound connection cleanup. Evidence: external billing-recovery-evidence/b01-original (4 tests, 4 failures, 0 errors/skips).

Correction: register application activity-resume callbacks inside BillingManager, coalesce callbacks through its owner scope, wait for an existing inventory/restore before refreshing, reconnect through the existing connection guard, and unregister/close/invalidate callbacks when that scope completes. No Activity is retained. Existing request-generation checks and cached entitlement on Play errors remain intact.

Verification: b01-fixed, app-host JVM billing package, 63 tests passed with 0 failures/errors/skips, including the four new foreground, overlapping-restore/recreation, reconnect/error and disposal cases. Debug compilation and pre-build security/architecture checks passed. No real Play purchase, physical device or signed runtime claim. Final lint, full JVM/native/CI and signed artifact verification remain B06.

Changed files: BillingManager.kt, BillingForegroundRecoveryTest.kt, this report. Commit identity is recorded externally after commit to avoid modifying the tested source to insert its own SHA.

## B02 - Acknowledgement recovery

Original reproduction: b02-original, two deterministic JVM tests failed: exhausted transient attempts left no scheduled recovery, and a thrown CancellationException was retried/swallowed. Existing three-attempt retry behavior was preserved as an immediate strategy rather than treated as inherently wrong.

Correction: bounded explicit acknowledgement outcomes, cancellation propagation, per-token deduplication and a bounded in-memory success cache. A private boolean hint and one network-constrained exponential-backoff JobScheduler job are installed before an attempt; no purchase tokens are persisted. Recovery re-queries authoritative Play inventory. It reuses a live UI manager, or owns a temporary manager which is retired before a foreground manager creates its client. Existing UI owners are not closed by other UI owners. Manager jobs are individually cancellable. Permanent failures pause automatic retry for explicit reconciliation. No new dependency, boot receiver or signing change.

Accepted b02-accepted: 73 billing/ad JVM tests passed, 0 failures/errors/skips, including 10 new acknowledgement/scheduling/session cases. Debug Android instrumentation sources compile, including three real platform scheduler/manifest contract tests. These native cases have not yet executed; four-API execution remains B06. Earlier b02-fixed: 71 JVM tests passed before the two additional owner cases. No live Play order or process-kill runtime claim.

Limits: system scheduling is not a deadline guarantee; force-stop, extended offline periods and OS refusal remain possible. The private marker repairs absent jobs on the next manager start, including after reboot; the job itself is not reboot-persisted. Recovery does not promise delivery while the user keeps the application force-stopped. Logs exclude tokens and provider exception messages.

Changed files: BillingManager.kt, BillingAcknowledgementRecovery.kt, BillingRecoveryJobService.kt, AndroidManifest.xml (private permission-protected service and explicit network-state permission), BillingAcknowledgementRecoveryTest.kt, BillingRecoverySchedulerIntegrityTest.kt, this report.

## B03 - Bounded restore and retry ownership

Original b03-original: all six new JVM cases failed on B02 source: missing callback never ended loading, late inventory could revoke Premium after a deadline, Retry loaded details instead of retrying restore, connection setup lost restore intent, pending orders were reported absent, and price callbacks overwrote active restore state.

Correction: independent connection/details/inventory deadlines; inventory generation invalidation on timeout; retained manual-restore intent; operation-specific retry; pending-aware outcomes; coalesced repeated Restore calls; and success publication after entitlement processing. Price responses update the price without replacing restore progress/outcome. Connection/inventory failures also release background delivery waiters for bounded retry.

Accepted b03-accepted-final: 81 billing/ad JVM tests passed, 0 failures/errors/skips, including eight restore cases and all B01/B02 regressions. Initial b03-fixed passed 79 tests; the next 81-case attempt exposed a B02 fixture expecting another Restore before its earlier query completed. The fixture now completes that query before proving its owner remains usable; the coalescing regression remains intact. No sleeps, increased timeout, skipped cases or real-account claim.

Changed files: BillingManager.kt, new BillingRestoreRecoveryTest.kt and shared virtual-time BillingTestSession.kt, the one B02 fixture completion, this report. Native/signed/final CI remain B06.

## B04 - Independent secure entitlement persistence

Original b04-original: four JVM reproductions failed for a false-returning grant write, an injected throwing store interrupting grant/acknowledgement, Retry not repairing the cache, and a silent failed revocation write. The concrete PlatformEntitlementStore already catches most storage exceptions; the throwing interface implementation is fault-injection robustness evidence, not a reported live-device incident.

Correction: verified ownership is applied in memory and clears ads before asynchronous cache work. A coordinator checks write results, retries at most three times automatically, preserves explicit Retry, and serializes off-Main blocking commits under a process-wide mutex/generation so old owner retries cannot land after newer decisions. Encryption/migration format is unchanged. Unreadable-cache exceptions fail closed and request Play restoration. A repaired cache restores offline entitlement. The billing UI combines ownership and storage state; four added MainActivity lines render a focused recovery notice in the Premium branch, where purchase controls are hidden. No Reader/ad policy change or UI redesign.

Accepted b04-accepted-final: 90 billing/ad JVM tests passed, 0 failures/errors/skips. Includes six manager persistence cases and three coordinator tests (bounded retries, cross-owner invalidation, real off-Main blocked/cancelled commit ordering). Three native Compose notice cases compile; runtime execution remains B06. Older synchronous fake-store tests explicitly inject their fake storage dispatcher; the production dispatcher is IO. One restore fixture now drains the reactive loading state before delivering its callback and retains the success-order assertion.

Retained attempts: b04-fixed had one fixture failure from reading combined UI before its scheduled emission. b04-accepted had a test thread-name assertion rejected by the coroutine debug suffix, which also prevented its gate from opening and caused a timeout/next-test exception receipt. The fixture now checks actual Thread identity and captures failures; the original 10-second deadlock guard is unchanged. No sleeps, timeouts enlarged, assertions removed or security checks weakened.

Limits: if durable storage cannot be updated, offline ownership after process death cannot be promised, and an old revocation record may remain on disk until storage recovers. The app reports this condition; Play inventory remains authoritative and the current session uses the latest decision. No plaintext fallback or signing/key changes.

Changed files: BillingManager.kt; new EntitlementPersistenceCoordinator.kt and PremiumBillingRecoveryNotice.kt; four-line Premium-only MainActivity wiring; BillingPersistenceRecoveryTest.kt, EntitlementPersistenceCoordinatorTest.kt, PremiumBillingRecoveryNoticeTest.kt; deterministic fake-storage/reactive-UI fixture updates; this report.

## B05 - Already-owned reconciliation and purchase guards

Original b05-original: nine JVM cases failed, including both ownership-error entry points, duplicate callbacks, pending/empty/unrelated inventory, restore-error recovery, and checkout attempts for existing/pending owners. The checkout case uses real Billing 8.0.0 ProductDetails and BillingFlowParams builders with a synthetic test product; the client is mocked, so no order or charge is created.

Correction: ITEM_ALREADY_OWNED from launchBillingFlow and onPurchasesUpdated delegates to bounded Restore. Duplicate ownership callbacks retain the current restore ticket. Only authoritative PURCHASED premium_upgrade inventory grants access; confirmed unacknowledged orders use B02 and cache persistence uses B04. Pending ownership is tracked separately, and checkout is rejected for known paid/pending owners. Price callbacks cannot re-enable purchasing for those states. No automatic second checkout.

Accepted b05-fixed: 99 billing/ad JVM tests passed, 0 failures/errors/skips, including all nine new cases and B01-B04 regressions. The resolved SDK builder was independently inspected: it already copies the one-time offer token from ProductDetails, so no speculative offer-handling or Billing dependency migration was introduced. Real Play purchase/restore remains an owner gate.

Changed files: BillingManager.kt, BillingAlreadyOwnedRecoveryTest.kt, synthetic real-SDK product fixture in BillingTestSession.kt, this report.

## Remaining planned work

- B06: complete final-source validation and replacement signed artifacts; owner Play/device gates separately recorded.
