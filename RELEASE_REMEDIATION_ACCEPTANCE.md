# Release remediation acceptance

Baseline: PR #21 head 00a1575879ae568b29cd199ae0d6c9f70216ef57. Branch: fix/2.1.0-release-remediation. The existing billing checkout and its untracked Vulcan handoff are preserved. No merge, tag, deployment or Play action.

## Task 1 - Monotonic interstitial timing

Confirmed: cooldown and cached-ad age used wall time. The default and reset now use Android elapsedRealtime; no elapsed timestamps are persisted. Nullable timestamps distinguish no previous display/load from elapsed time zero. Cooldown remains 60 seconds and expiry remains 55 minutes.

Original task1-original-local: four new JVM regressions, four failures, zero errors/skips. Fixed task1-accepted: 103 billing/ad JVM tests passed, zero failures/errors/skips. Tests verify default/reset clock wiring, a wall-clock advance without cooldown bypass, exact 60-second admission, first display at elapsed zero and expiry of an ad loaded at zero. The original sandbox attempt eventually completed with the same four expected failures; the separate accepted local run is authoritative. The initial fixed full-package run exposed a fixture resetting cooldown with the old zero sentinel; it now explicitly clears previous-display state with null. The timing assertions remain intact.

Changed files: AdManager.kt, AdMonotonicClockTest.kt, AdOpportunityDeliveryTest.kt, this report. No real ad request/display is asserted. Four-API, complete final-source and signed replacement acceptance remain pending after the remaining tasks.
