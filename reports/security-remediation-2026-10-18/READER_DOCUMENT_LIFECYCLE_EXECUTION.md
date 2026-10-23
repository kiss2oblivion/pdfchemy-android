# Reader document handoff and startup ordering

Release: HOLD. Base source: cb7ff0473ec3c420d6caf2fcca53519030910116.

The exact signed base APK opened a benign PDF in Reader and Editor, then displayed Document unavailable after Back. The form-alias fixture also encountered the failure; its original input hash was unchanged. The base passed all nine CI jobs and 1,096 native executions, but existing Reader navigation coverage checked its destination rather than composing the actual Reader workflow. Its signed archive is superseded.

The Edit action did not retain the staged capability. Returning from a tool cleared continuity before the new Reader acquired a lease; refreshing the same continuity URI could close the last lease and immediately delete a snapshot pending release. Reader also launched outline extraction alongside audit/page-count requests despite single-flight worker admission.

Edit now retains the snapshot and records that capability as the return destination. The returning Reader acquires its own staging lease before consuming matching continuity. Repeating the same handoff URI is idempotent, and a stale Reader cannot clear another document's handoff. Reader runs audit, page count and outline in sequence before enabling Edit and uses the staged snapshot for outline extraction. Leaving Reader releases the snapshot normally.

## Verification snapshot

- Baseline staging/session suite: six methods, one expected assertion failure for same-URI refresh, zero errors. The original signed-UI failure is retained separately.
- Corrected staging/session suite: eight methods pass, including same-URI pending release, safe return ownership, and stale-URI isolation.
- Complete host suite: 271 methods, zero failures/errors/skips.
- Security audit and architecture tasks pass. Release lint: zero errors, 640 warnings.
- Actual MainApp native regression: one method passes on the owned, offline, settled API36 emulator with Vanguard enabled. It requires a visible rendered page, completes Reader -> Editor -> Reader -> Home, verifies snapshot survival, and checks deletion on final exit.
- The first native test attempt failed at an immediate page-image visibility assertion. Its evidence is retained; the corrected test waits for the asynchronous bitmap layout and passed. The production change was unchanged between those two native attempts.

Raw evidence remains outside the checkout in signed-launch-api36-cb7ff047/, reader-handoff-baseline-results/, reader-handoff-unit-baseline-2.log, reader-handoff-focused-build-2.log, reader-lifecycle-api36-fixed-1/, reader-lifecycle-api36-fixed-2/, reader-handoff-full-host-lint.log and reader-handoff-full-results/. Portable source hashes and result counts are in evidence/READER_DOCUMENT_LIFECYCLE_RECEIPT.json.

This snapshot precedes its commit/push, new exact-source CI and new signed build. The final candidate requires 275 native methods per API, including this actual Reader lifecycle method. Debug native verification does not certify a minified signed APK, physical device, Play flow, cold startup or SR-01 owner decision. Those gates remain separate.
