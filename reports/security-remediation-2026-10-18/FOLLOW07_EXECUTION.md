# FOLLOW-07: SR-01 storage-risk disposition

Updated 2026-10-23. Release: HOLD. Status: OPEN.

## Correction to the earlier record

An earlier generated revision stated that the named project owner had signed and accepted this residual risk. No attributable approval receipt for that statement is present in this release-preparation session. That statement is superseded here. An actual owner decision has been requested; the request and a preselected answer are not approval.

The earlier 300-800% proxy-descriptor overhead estimate had no benchmark implementation, device, input or measurements. It must not be treated as measured evidence or used to reject a candidate boundary.

## Current guarantee and remaining risk

The host intentionally supplies admitted document input descriptors and writable temporary output/scratch descriptors to an isolated worker. Isolation does not mean the worker has no access to document bytes. User destination publication remains a host operation.

OperationScratchBroker limits scratch-descriptor cardinality to 64 and unlinks created files. HostOutputTransaction and WorkerResponseValidator reject invalid or oversized output before publication, including the 250 MiB aggregate output limit. These checks and cooperative counters are distinct from continuously enforcing aggregate backing allocation while arbitrary worker code holds writable regular-file descriptors.

The residual scenario assumes arbitrary native execution inside the isolated worker. A compromised worker can ignore cooperative accounting and consume storage through the supplied descriptors before post-execution checks run. No successful sandbox compromise is claimed by this assessment, and no deliberate real-volume exhaustion experiment was performed.

Unlinking removes a pathname but does not reclaim an inode while another descriptor still refers to it. Therefore directory cache cleanup is not a guaranteed recovery mechanism for already-unlinked files held by a worker. Reclamation requires the remaining descriptors to close, including those held in the worker.

## Candidate stronger boundaries

| Candidate | What is established | Still required |
|---|---|---|
| Hard RLIMIT_FSIZE | A hard per-file ceiling is different from cooperative counters; it is not by itself an aggregate storage budget | Safe write/truncate/preallocation tests, descriptor/aggregate exposure analysis and real-document compatibility |
| Seekable host-mediated proxy descriptors | Android provides offset-based proxy callbacks from API26; lack of pipe seeking is not a blanket reason to reject them | Native-library compatibility, bounded backing allocation, error/cancellation recovery, measured performance and an API24/25 strategy |
| Filesystem quota | No supported portable application-controlled quota has been demonstrated for this project | Actual unprivileged API/device evidence before selecting this approach |

No candidate is declared fully implemented or benchmarked here. Existing parser/render deadlines and process recycling remain useful defenses but are not a hard hostile-worker storage allocation quota.

Primary references: [Android StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,android.os.ProxyFileDescriptorCallback,android.os.Handler)), [Linux unlink semantics](https://man7.org/linux/man-pages/man2/unlink.2.html), and the project's [technical reassessment](../security-code-reaudit-2026-10-10/SR-01_STORAGE_RESIDUAL_DECISION.md).

## Required disposition

Keep FOLLOW-07 open until either stronger storage enforcement is implemented and verified over supported APIs and documents, or the owner explicitly accepts the described residual availability risk with attributable evidence. That decision does not replace the separate final owner release decision or authorize a Play upload.
