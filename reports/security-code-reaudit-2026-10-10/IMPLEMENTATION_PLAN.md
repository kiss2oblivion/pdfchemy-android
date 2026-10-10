# Android security implementation plan — 2026-10-10

Source baseline: `80ecd0ba00d209daee14ce6613820b7b715c608c`. Findings: [SECURITY_CODE_REAUDIT.md](SECURITY_CODE_REAUDIT.md). Security implementation is proposed here; no finding is represented as fixed. Preserve PDF jail/native isolation, typed response validation, quotas, immutable staging, private output snapshots, consent gates, offline document processing and user-controlled history.

## Phase 0 — Finish and verify the startup correction

Priority: immediate. Original observed failure: API 30 PhoneWindow.generateLayout cannot find the content container during MainActivity.onCreate/setContent.

- Reconcile any queued local edit before applying another patch. Do not assume an interrupted edit request succeeded.
- Move UMP consent/ads startup out of pre-setContent onCreate into a once-per-activity post-content lifecycle point such as onPostResume. Keep SDK calls on the required thread and suppress callbacks against a finishing/destroyed activity. Preserve canRequestAds checks and consent-information refresh.
- Do not broadly catch the layout exception, retry inflation blindly, remove consent or bypass the splash theme.
- Add a native launch/recreation regression that checks the actual android.R.id.content hierarchy with normal SDK startup enabled. Exercise repeated cold launches, recreation and background/resume, including existing-consent and first-run states.
- Run focused startup and original AudioWavPlatformTest on API 30; run relevant build/lint/diff checks; then verify API 24/34/36 compatibility and same-source CI.
- Record this as an ordering correction until reproduction/instrumented evidence supports attributing the old failure to that ordering. An intermittent failure disappearing once is not proof of root cause.

Expected source ownership: MainActivity startup methods, dedicated startup instrumentation test, startup validation receipt. Security policy changes are excluded from this phase.

Exit: verified implementation commit and remote SHA, no content-container failure in defined stress runs, original audio launch regression passing, consent behavior preserved. Currently blocked on local command/edit verification; no fixed claim is authorized.

## Phase 1 — Destination integrity (SA-01)

Priority: P1. Affecting UI operation cleanup and audio publication.

1. Introduce an output capability/ownership record: URI, operation identity, fresh-copy provenance and publication state. A system picker URI or caller-supplied URI is existing/unknown unless freshness can be proven.
2. Preferred folder creation already creates a fresh copy and rejects a URI found in its pre-existing listing. Carry that fact forward instead of erasing it to a bare URI.
3. On cancellation, stop/join the owned writer, then delete only proven fresh incomplete destinations on IO. Keep existing/unknown destinations intact before publication. Apply the same rule to error cleanup, initialization rejection and audio cleanup.
4. Preserve HostOutputTransaction's validate-all/snapshot/ACK-before-publication behavior. Do not grant real provider FDs to workers.
5. Describe provider I/O failure after publication starts accurately. Do not promise restoration where the provider cannot transact.

Expected files: PreferredDocumentCreator/OutputPolicy, MainViewModel operation/output ownership, audio destination dependency/publication/service paths, relevant save callbacks and tests. Determine the exact callback migration inventory from the current checkout before editing.

Acceptance tests: existing destination seeded with bytes survives pre-publication worker failure, cancellation, deadline, invalid response, audio initialization rejection and source-read failure; fresh incomplete destinations are removed; successful writes retain output; stale cancellation cannot delete another operation's destination; source==destination is rejected or deliberately protected; pipe/non-seekable provider cases remain supported.

## Phase 2 — Window privacy ownership (SA-02)

Priority: P1. Small, coherent independent batch after destination integrity.

Use a window-scoped lease manager or single activity policy for FLAG_SECURE. It must support overlapping sensitive compositions, idempotent release and destruction/recreation. Preserve a flag set by another owner. Avoid a global process Boolean spanning multiple windows.

Expected files: SecureScreen.kt, the policy's window/activity integration if needed, and native transition tests.

Acceptance: secure -> secure transitions never clear the flag; secure -> ordinary clears only after the last lease; rapid transitions/Back and overlapping dialogs/compositions preserve protection; recreation restores it; a previously secure window remains secure after the helper releases its own lease. Test API 24/30/34/36 and separately verify physical screenshots/task snapshots.

## Phase 3 — Input lifetime and bounded sessions (SA-03)

Priority: P2. Reconcile with existing DocumentStager leases and Reader/tool continuity.

Represent active Reader/editor/tool ownership explicitly. Release superseded input only after every live operation/session borrower ends. Ensure stage cancellation and failed/encrypted picker dismissal release newly owned snapshots. Bound retained positions/session entries where they unnecessarily keep capabilities alive.

Expected files: Reader/picker/Editor continuity integration and staging ownership model; preserve the stager's immutable registry and current copy-time/hash/size quotas.

Acceptance: repeated distinct opens/retries in one activity do not exhaust the stage registry or byte budget; the occupancy returns to a documented live-session bound; Reader -> tool -> Reader and editor recreation retain the right input; an active worker's lease prevents deletion; release is idempotent; failed selection does not replace current input; external incoming intents cannot accumulate abandoned stages.

## Phase 4 — Existing residual disk risk (SR-01)

This is already documented under SEC-009, not a newly found regression.

First write a feasibility note comparing bounded host-mediated writes, kernel/filesystem enforcement where available, and capacity admission/monitoring. State clearly that cooperative worker streams and polling do not bound a compromised process holding writable FDs.

Use a controlled test device/emulator with an explicit small storage budget for adversarial tests; do not fill the user's real volume. Decide whether to implement a stronger boundary or retain a justified residual risk. Preserve isolation and existing scratch/publication controls.

Exit: approved design plus adversarial evidence, or explicit risk acceptance and operational limits. Do not silently mark it closed.

## Phase 5 — Follow-up hardening and verification gaps

- Redaction: native search -> review -> export tests with crop origins, four rotations and multiline targets; assert selected content is absent from extraction and pixels in secure mode. Make non-forensic opt-out unmistakable while preserving the existing explicit option.
- Billing: check/retry acknowledgment results; decide a purchase-verification design compatible with the offline model. Client-only checks are not protection against a fully compromised host. Verify pending/cancelled purchases never grant entitlement.
- Incoming URI trust: audit source admission against grants/allowed schemes and private-file confused-deputy scenarios; implement only after establishing reachability and the required supported workflow.
- Consent/privacy: complete repository-wide wiring review and UMP privacy-options verification. Do not infer absence from the paths inspected in this audit.
- Dependency/secrets: re-run resolved production dependency inventory/OSV and reachable-history secret policy. Do not classify public client identifiers as secrets without evidence.
- Audio: verify cancellation and destination freshness through the same ownership contract; retain private service/immutable PendingIntent/offline-voice selection. Document trust in installed third-party TTS engines.

## Batch discipline and completion criteria

For every implementation batch: reconcile source first; report exact files; map finding IDs; run meaningful focused tests plus relevant build/lint; inspect git diff --check; commit/push only to kiss2oblivion/pdfchemy-android; verify remote SHA and same-source CI. Never merge as part of this plan.

The audit report/plan are documentation-only, so they do not establish a production fix or require rerunning unchanged APK builds. No new native/build/secret/dependency results were available when this plan was written.

Completion requires SA-01/02/03 closed with evidence or explicitly deferred with rationale, SR-01 disposition recorded, and every verification gap either tested or visibly retained. Keep the six preceding UX re-audit defects tracked separately; do not treat this security plan as their closure.
