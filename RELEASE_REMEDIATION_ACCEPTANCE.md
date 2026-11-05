# Release remediation acceptance

Baseline: PR #21 head 00a1575879ae568b29cd199ae0d6c9f70216ef57. Branch: fix/2.1.0-release-remediation. The existing billing checkout and its untracked Vulcan handoff are preserved. No merge, tag, deployment or Play action.

## Task 1 - Monotonic interstitial timing

Confirmed: cooldown and cached-ad age used wall time. The default and reset now use Android elapsedRealtime; no elapsed timestamps are persisted. Nullable timestamps distinguish no previous display/load from elapsed time zero. Cooldown remains 60 seconds and expiry remains 55 minutes.

Original task1-original-local: four new JVM regressions, four failures, zero errors/skips. Fixed task1-accepted: 103 billing/ad JVM tests passed, zero failures/errors/skips. Tests verify default/reset clock wiring, a wall-clock advance without cooldown bypass, exact 60-second admission, first display at elapsed zero and expiry of an ad loaded at zero. The original sandbox attempt eventually completed with the same four expected failures; the separate accepted local run is authoritative. The initial fixed full-package run exposed a fixture resetting cooldown with the old zero sentinel; it now explicitly clears previous-display state with null. The timing assertions remain intact.

Changed files: AdManager.kt, AdMonotonicClockTest.kt, AdOpportunityDeliveryTest.kt, this report. No real ad request/display is asserted. Four-API, complete final-source and signed replacement acceptance remain pending after the remaining tasks.

## Task 2 - Read-only sharing with separate camera write capabilities

Confirmed conditional defect: non-retained FileProvider roots accepted writable opens when given a write grant. Normal share intents already grant read only; no ungranted foreign access is claimed. The retained-copy hook already rejected writes; the fallback did not. A guard now rejects every mode except r before dispatch, and provider deletion is denied so broader grants cannot bypass reader-aware backend cleanup. Existing shared URI roots remain readable.

The requested blanket guard alone would break Images-to-PDF TakePicture, which intentionally gives a camera app a writable scan URI. That capability now uses a separate non-exported, URI-grant-only CameraCaptureFileProvider, exposing only cache/scans. The read-only provider retains its original authority and roots. No broader private path is exposed. External intent admission continues to use installed provider UID ownership.

Original task2-original-fallback-native: three native cases failed on the preserved original APK. Actual foreign-UID writable fallback open succeeded; the camera helper still used the shared authority; host write-mode rejection failed. Initial fixture run used a shared_ export name, exercising the already-protected retained hook instead of the intended fallback; it is retained but superseded by the corrected original reproduction. The recipient writer also uses an owned duplicated descriptor rather than closing a borrowed raw FD.

Accepted task2-accepted-native: 13 native tests passed (three mode/camera cases plus ten retained management cases), zero failures/errors/skips, owned offline API36. All four shared roots reject w/wt/wa/rw/rwt without changing bytes; a foreign UID with both grants cannot write or delete, but can reopen for reading; camera writing succeeds under its separate authority and cannot map exports. Retained leases/accounting/explicit cleanup remain intact. Original installed APK restored and hash checked after every native attempt. SecurityAudit/securityArchitecture and six focused ownership/sharing JVM tests passed in task2-fixed-build. Final four-API and signed replacement checks remain pending.

Changed files: RetainedShareFileProvider.kt, CameraCaptureFileProvider.kt, camera_capture_paths.xml, AndroidManifest.xml, one camera URI authority line in CreateScreens.kt, ShareProviderModeIntegrityTest.kt, test-only ShareRecipientProvider.java and this report. A physical camera application was not exercised.
