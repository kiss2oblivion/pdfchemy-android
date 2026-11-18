# Priority issue remediation - 18 November 2026

Baseline: `72e8a363fbd29efdc1c9145e160a5c9078e54e78`.
Scope: the seven DA findings in the repository analysis of 18 November 2026, plus diagnosis of the known API 34 sanitizer CI failure.

| Issue | Implementation and focused verification |
|---|---|
| DA-01: sanitizer metadata removal | Complete: bounded alias-aware cleanup, broader audit detection, saved-graph validation; eight new unit regressions; 41 focused unit tests passed; worker lint passed (one existing UseKtx warning); APK/test APK builds passed; three real-worker API 34 sanitizer scope tests passed, including keep/purge metadata. |
| DA-02: aliased attachment payloads | Complete: selected payload identities and descendant aliases removed before carrier scrubbing; combined purge policy and saved-payload validation; eight new unit regressions; 49 focused unit tests, worker lint and APK builds passed; four API 34 sanitizer scope tests passed. |
| DA-03: metadata/compression shallow wipe | Pending |
| DA-04: sanitizer request ownership | Pending |
| DA-05: stale inspection state | Pending |
| DA-06: child attachment name trees | Pending |
| DA-07: blocking provider metadata queries | Pending |
| Exact-source CI and final integration checks | Pending; previous main API 34 timeout remains unresolved. |

Focused tests do not establish store readiness. Signed-candidate, Play acceptance and physical-phone checks retain their separate status. No store publication is part of this task.
