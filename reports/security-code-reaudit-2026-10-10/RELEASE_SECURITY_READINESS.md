# Release security readiness — 2026-10-10

**Release decision: HOLD.** This review establishes open source findings and the evidence needed to close them. It does not certify an APK/AAB for public release.

## Source and scope

Remote main: `4b3c157f3458f7df00ea94044258d33c7449ced9`.
Implementation candidate: `80ecd0ba00d209daee14ce6613820b7b715c608c`.
Prior tested head: `7aca098a02edd246b914669fae542967cd5608fc`.
The tested-to-candidate comparison contains only three audit Markdown files; production source, tests and build inputs are identical. Main and implementation are distinct revisions: candidate CI is not a test of every main-only difference.

The preceding [code audit](SECURITY_CODE_REAUDIT.md) inspected isolated workers, IPC, staging, output publication, UI privacy, audio, redaction, archives, billing, manifests and logging. This continuation rechecked main's destination cleanup and SecureScreen, candidate manifests/FileProvider, release configuration and prior certification records, scanner implementations, destination creation, staging and Settings privacy-options wiring. Source findings have not been reproduced as exploits on a device. Backend rules, Play Console, signing infrastructure and third-party native internals remain outside scope. Local shell/edit execution is still unavailable; no new local test/build/diff-check is claimed.

## Open code findings

| ID | Priority | Current evidence and required closure |
|---|---|---|
| SA-01 | High / P1, conditional on existing destination capability | MainViewModel tracks bare output URIs and deletes them after error/cancellation. Audio cleanup has the same provenance assumption. Preserve existing/unknown destinations before publication; delete only proven fresh incomplete copies. Test seeded existing bytes, fresh copies, cancellation, failed initialization and stale operation cleanup. |
| SA-02 | High / P1 | SecureScreen adds a shared window flag and clears it on disposal. AnimatedContent overlaps Reader/Editor consumers, so outgoing disposal can remove incoming protection. Use window-scoped ownership; test overlapping transitions, Back/recreation and actual capture. |
| SA-03 | Medium / P2 | Successfully staged documents can outlive Reader/picker sessions until activity finish. The 64-document/250-MiB quota then rejects subsequent work. Add scoped ownership while preserving live worker/tool leases; stress repeated opens and handoffs. |
| SR-01 | Known conditional availability residual, SEC-009 | A compromised worker holding writable output/scratch FDs can bypass cooperative limits before host verification. Decide stronger allocation enforcement or explicitly accept this documented residual; polling is not a hard quota. |

SA-01 qualification: Android's [Intent API contract](https://developer.android.com/reference/android/content/Intent#ACTION_CREATE_DOCUMENT) permits an existing returned document, while the [storage tutorial](https://developer.android.com/training/data-storage/shared/documents-files#create-file) describes numbered new files. No ordinary same-name save is asserted to overwrite. A returned URI alone does not prove freshness. Preferred-folder creation checks for a separate copy, but its callback erases that provenance. Provider-specific reachability still needs reproduction.

SA-02 concerns loss of Android's [window capture protection](https://developer.android.com/security/fraud-prevention/activities), not proof of arbitrary app access to document bytes.

The API 30 startup content-container crash remains unresolved: no verified production fix or root cause. A proposal stored beside this report is an ordering hypothesis, not a completed fix.

## Validation evidence

[CI run 37764572638](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37764572638), head `7aca098a02edd246b914669fae542967cd5608fc`:

- **Fresh, 2026-10-10 18:41:22 UTC:** dependency job `114285038779` passed; 234 resolved production dependencies, 0 OSV affected packages. This is a database result at that time, not proof that dependencies are vulnerability-free.
- **Historical, 2026-10-08:** architecture and unit gates passed; instrumentation completed 209 tests with zero failures on each API 24/30/34/36. These suites do not close the newly identified findings.
- **Historical, 2026-10-08:** TruffleHog 3.97.9 reachable-history policy passed: 6 exact reviewed historical matches, 0 rejected findings. This continuation has not yet executed a fresh secret scan.
- **Fresh build job `114285337630`: pending final receipt.** It runs release lint, debug/unsigned minified release assembly and artifact hygiene. It does not produce a signed Play upload certificate verification.

The release checker enforces corpus hashes, 62 route coverage and artifact hygiene. It does **not** turn NOT RUN manual matrix rows into PASS. No retained Actions artifacts were returned for this run.

Inspected source preserves private isolated PDF/native services, host-only provider publication, immutable bounded staging, scoped FileProvider directories, disabled backup and R8/resource shrinking. Settings does expose UMP privacy options when required (MainActivity 3467–3474); the preceding wiring question is therefore resolved at source level. Consent update/error/revoke flows and third-party SDK behavior still need runtime evidence. Incoming file URI admission and search-generated redaction geometry remain follow-up verification gaps; no private-file exfiltration or redaction exploit is claimed.

## Ordered release plan

1. Verify the startup correction with normal SDK startup, repeated launch/recreation and the original API 30 audio launch test.
2. Fix SA-01 and SA-02 in separate reviewed batches; close SA-03 with lifetime/continuity stress tests. Run focused tests, relevant build/lint, diff-check and same-source CI; push only to the Android repository. No merge is authorized here.
3. Record SR-01 disposition. Test secure search redaction across crop/rotation/multiline cases, incoming URI admission, billing acknowledgment/purchase flow, consent/privacy revocation and audio provider failures.
4. Run current resolved-dependency and reachable-history secret gates against the final release commit.
5. Build and verify a signed/minified APK and AAB from that exact clean commit. Prior October 7 signed artifacts are older evidence.
6. Reconcile the Play **upload** certificate with the actual signing key. Existing local certificates differ; Console registration is unverified. Confirm version code 13 availability.
7. Execute signed physical smoke: all 62 current routes, the 14 committed PDFs, audio scenarios, fresh install/upgrade and screenshot/task-snapshot checks. Complete missing producer/original-incident compatibility evidence. Finish required Play foreground-service declaration/video and accurate data-safety disclosure.

Release closes only when open findings and startup have verified dispositions, final artifact hashes/signatures are recorded and the external/device gates have actual evidence. Green CI alone does not close these gates.
