# Startup correction proposal — not applied or verified

Frozen base: `80ecd0ba00d209daee14ce6613820b7b715c608c`. Local runner/edit calls stopped returning; no verified production commit or runtime result exists.

The implementation proposal moves the existing consent/ads block from pre-setContent onCreate to a once-per-activity onPostResume. Existing screenshot-run and canRequestAds behavior remains; splash setup and PDF security boundaries are unchanged. This removes an SDK-before-content ordering hazard. The intermittent old PhoneWindow failure has not been causally reproduced, so this must not be represented as a proven root-cause fix until tested.

Use the adjacent proposed source and test as reviewable inputs. Before applying, reconcile the actual local MainActivity and any previously queued edit. Do not copy over unrelated changes. The full proposed source is based on the frozen commit; the only intended production change is moving the existing SDK block and adding its guarded lifecycle entry point.

Proposed instrumentation covers 10 normal launches plus recreation, and 10 background/resume cycles with screenshot-run bypass absent. It is currently uncompiled/unexecuted. Add first-run/cached-consent-specific cases after checking the test environment.

Required checks: focused StartupWindowRegressionTest and the original AudioWavPlatformTest on API 30, debug/androidTest assembly, relevant lint, diff --check, then Android 24/34/36 and same-source CI. Commit/push production changes only after the required checks. The passing historical CI does not validate this proposal.

Official references: [UMP setup and duplicate request guidance](https://developers.google.com/admob/android/privacy), [Android window API](https://developer.android.com/reference/android/view/Window). These references do not establish the cause of the original crash.
