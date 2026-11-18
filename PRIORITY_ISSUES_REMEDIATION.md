# Priority issue remediation - 18 November 2026

Baseline: `72e8a363fbd29efdc1c9145e160a5c9078e54e78`.
Scope: the seven DA findings in the repository analysis of 18 November 2026, plus diagnosis of the known API 34 sanitizer CI failure.

| Issue | Implementation and focused verification |
|---|---|
| DA-01: sanitizer metadata removal | Complete: bounded alias-aware cleanup, broader audit detection, saved-graph validation; eight new unit regressions; 41 focused unit tests passed; worker lint passed (one existing UseKtx warning); APK/test APK builds passed; three real-worker API 34 sanitizer scope tests passed, including keep/purge metadata. |
| DA-02: aliased attachment payloads | Complete: selected payload identities and descendant aliases removed before carrier scrubbing; combined purge policy and saved-payload validation; eight new unit regressions; 49 focused unit tests, worker lint and APK builds passed; four API 34 sanitizer scope tests passed. |
| DA-03: metadata/compression shallow wipe | Complete: both tools use the bounded graph cleanup and validate serialized privacy output before publication; direct-object fixture regression; 27 focused unit tests, worker lint and APK builds passed; 11 API 34 worker tests passed across deep cleanup, sanitizer scope and existing metadata behavior. |
| DA-04: sanitizer request ownership | Complete: generation guards for reports/errors/finally, cancellation and serialized worker admission, immutable save snapshots, obsolete destination cleanup and explicit main-thread UI continuations. Six focused unit tests, host lint and APK builds passed; 13 API 34 cases passed twice (five controlled ownership cases, four publication integrity cases and four real-worker scope cases). One prior wrong-thread failure was retained and corrected before commit. |
| DA-05: stale inspection state | Complete: keyed cancellable inspections, generation guards, immediate data/error reset, explicit failure states and immutable source/item/options snapshots across metadata, fonts and attachments. Six focused unit tests, host lint and APK builds passed; 17 API 34 cases passed, including 12 real-engine inspector cases covering failures, stale destinations, an obsolete extra-file picker and positive metadata/font/extract/remove/embed outputs with source preservation. A fixed-footer test action was corrected before closeout. |
| DA-06: child attachment name trees | Pending |
| DA-07: blocking provider metadata queries | Pending |
| Exact-source CI and final integration checks | Pending; the previously failing API 34 sanitizer publication case now passes locally. Exact-source full CI and broader integration verification are still required. |

Focused tests do not establish store readiness. Signed-candidate, Play acceptance and physical-phone checks retain their separate status. No store publication is part of this task.
