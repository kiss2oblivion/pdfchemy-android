# Batch IPC boundary acceptance

## Scope and evidence

Base: PR #22, `5daba23f08b17ea8f6fc7d90c19070dc55bb901c`.
Work: `fix/2.1.0-batch-ipc-boundary`, stacked PR #23. PR #22 and local worktrees are preserved.

The original gateway already bounded descriptor counts at 32 before staging and caught/cleaned failed requests. No public-workflow TransactionTooLargeException crash has been reproduced. Descriptor count alone does not establish Binder safety: Android shares a finite transaction buffer, and inline JSON is a significant part of this request. This patch implements a conservative admission policy, not a guarantee under arbitrary system load or a claim of invulnerability.

A reachable planning mismatch was confirmed in source: split/blank-page split/bookmark split/PDF-to-images accepted up to 500 outputs and created destinations before encountering the gateway's smaller descriptor cap. The new limit is checked before output creation. Image-to-PDF reports an actionable error before gateway dispatch.

## Original reproduction

Tests-only commit: `7992b1ed6e0b3703150fc8e4e61fb607d4fedf0d`.
[Baseline CI](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/42214109359).

The IPC JVM task compiled and ran 113 tests: 3 failed, 110 passed. Failing cases:
- BatchDescriptorLimitTest.batchDescriptorContractIsSixteen
- BatchDescriptorLimitTest.seventeenthSourceIsRejected
- BatchDescriptorLimitTest.seventeenthTargetIsRejected

The task stopped at that module; host JVM regressions were not executed by this failed command. Baseline architecture, secrets and dependency-security jobs passed. Baseline native execution and final-source results are recorded in PR metadata once available. These assertion failures reproduce the requested stricter contract, not an actual Binder exhaustion crash.

## Correction

- At most 16 input and 16 output descriptors per request.
- A separate 32 KiB UTF-8/character batch-JSON ceiling, enforced before any gateway I/O and independently at worker admission.
- Existing 256 KiB non-batch/editor request allowance, identity validation, aggregate input/storage quotas, exclusive operation tokens, pipe collectors, cancellation, abort and host-controlled publication remain.
- Split/image plans reject excessive output counts before creating provider files.
- Image-to-PDF rejects excessive inputs inside its existing error/cleanup contract.
- No AIDL schema change, retry after ambiguous completion, or partial-output chunking.

Compatibility: batches of 17-32 files now fail with a clear limit message. A PDF-to-images export over 16 pages is rejected; page-range split may select at most 16 output pages. Larger batch support requires a separately designed transaction-preserving protocol, outside this patch.

## Regression coverage

JVM:
- Exact descriptor cap; 16 accepted; 17 input/output rejected.
- Identity and aggregate byte quotas retained.
- All four output planners reject before provider creation.
- Image-to-PDF rejects before gateway execution, preserves an existing target and emits no advertising opportunity.

Android:
- Actual isolated-worker 16-input merge and 16-output split with maximum batch JSON; reopen every output.
- 16-output image export; decode every page.
- 17 inputs/outputs and one-byte JSON excess rejected before provider, scratch or Binder access, with source/existing-target bytes unchanged.
- Generated AIDL proxy maximum-envelope measurement under 96 KiB. This is envelope measurement, not available kernel-buffer measurement.
- Independent worker rejection of excess batch JSON without destination publication.

## Verification and limitations

Final commit, tree, full JVM/API24/API30/API34/API36/release-build results and CI URLs belong in PR #23 metadata to avoid changing the tested source merely to insert its own SHA. Required checks remain pending until actual completion.

The local command-execution service is unavailable, including basic read-only commands. Source was read through repository tools; the new branch was created from the exact preserved base using Git tree/commit APIs and a non-forced, expected-head ref update. No local file or unrelated branch was overwritten.

Existing signed APK/AAB evidence at PR #22's SHA does not verify this changed source. New signed/minified runtime acceptance and regenerated signed artifacts remain pending. Physical-device, Play/Billing/UMP and owner publication gates remain separate. No merge, tag, upload, deployment or signing-key change is performed.
