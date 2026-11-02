# SR-01: compromised-worker temporary-storage residual risk

Updated 2026-10-18 against remediation source commit `9d7dc22dd9ed38fb03ab50c52ac853f0e3fa72f9`. **OPEN: technical evaluation and attributable owner disposition required. Release HOLD.** This supersedes the earlier assessment's unsupported termination, validation and mitigation-rejection claims. It records source review and feasibility questions, not a new hostile-worker experiment or risk acceptance.

## Threat and observed boundaries

Assume arbitrary native execution inside an isolated PDF worker. The host transfers writable anonymous regular-file descriptors for temporary output and scratch storage. A compromised worker can use those capabilities without obeying cooperative worker byte counters. File-descriptor count and post-execution size checks do not enforce an aggregate allocation limit while a hostile worker holds writable descriptors.

The inspected source provides these narrower guarantees:

- `OperationScratchBroker` limits its allocated scratch descriptors to `MAX_SCRATCH_FDS` (64), immediately unlinks created files and checks their aggregate logical size in `verifyBudget()` after execution. This is a rejection check, not continuous physical-storage enforcement.
- `HostOutputTransaction` checks regular-file type, logical sizes and response JSON, creates host-only snapshots, and enforces `MAX_OUTPUT_BYTES` (250 MiB aggregate) before publishing snapshots to destinations. `WorkerResponseValidator` also validates the typed response contract. The host does **not** perform full PDF structural verification; parsing remains isolated.
- Workers intentionally receive admitted document input descriptors. User destination URIs are kept out of the PDF worker transaction; temporary output descriptors are sent instead. This is scoped capability access, not zero access to user document contents.
- Unlinking removes directory names; it does not reclaim blocks while descriptors remain open. Closing host descriptors alone does not close copies held by a worker. Reclamation depends on the last reference closing.

## Deadline correction

`WorkerGate` timers execute in the worker process, with termination callbacks such as `Process.killProcess(Process.myPid())`. The gateway has a response deadline and requests `abortOperation` on failure; that Binder method is `oneway`. The native renderer coordinator's binding timeout covers service acquisition, while renderer execution uses synchronous Binder calls and worker-local timers.

These are useful defenses for faults in cooperative workers. This review has not demonstrated a non-bypassable 30-120 second lifetime bound against arbitrary native execution in the process containing the timer. The earlier claim that the host guarantees hostile-worker termination within that window is withdrawn. Neither unbinding nor the presence of a timeout alone proves that every hostile descriptor copy has closed. No absolute statement that the security boundary is fully preserved is supported by this assessment.

## Mitigation assessment reopened

| Candidate | Corrected assessment | Required evidence |
|---|---|---|
| Hard `RLIMIT_FSIZE` | An unprivileged process cannot raise a lowered hard ceiling without the necessary privilege. The previous claim that arbitrary worker code can simply lift it was incorrect. It limits file length; it is not by itself an aggregate physical-storage quota. | Check actual worker capabilities, install the limit before untrusted parsing, exercise write/truncate/preallocation behavior and multiple descriptors with a small safe budget, then evaluate compatibility and aggregate exposure. |
| Seekable host-mediated descriptors | Android offers `StorageManager.openProxyFileDescriptor` from API 26, including offset-based callbacks. A blanket rejection based on pipes being non-seekable overlooked this candidate. Feasibility for these native libraries is unverified. | Test cross-UID transfer, seek/pread/pwrite/truncate/fsync and reopen behavior, bounded backing allocation, quota errors, cancellation, recovery and performance. Define an API 24 strategy before claiming coverage. |
| Filesystem quota | No portable application-controlled filesystem quota has been established for supported devices in this project. | Demonstrate a usable unprivileged API and device coverage before choosing it. |
| Existing checks and anonymous files | Retain admission, descriptor limits, snapshots and rejection of oversized results. They protect publication and reduce exposure but do not establish the missing hostile allocation bound. | Keep existing regressions; distinguish logical file sizes, allocated blocks, publication limits and descriptor lifetime in new tests. |

The hard-limit correction follows [Linux getrlimit/setrlimit documentation](https://man7.org/linux/man-pages/man2/getrlimit.2.html). Seekable proxy feasibility follows the Android references for [StorageManager.openProxyFileDescriptor](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler)) and [ProxyFileDescriptorCallback](https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback). Those APIs establish candidates, not compatibility or a verified fix for PDFchemy.

## Completion gates

1. Run controlled hostile-worker storage experiments using a deliberately small budget on isolated emulators; never fill a user's disk to demonstrate exhaustion.
2. Compare stronger enforcement candidates using actual worker I/O and all supported API levels, recording unsupported operations and error handling.
3. Choose and verify an implementation, or present the remaining concrete availability exposure for attributable owner review. Do not infer acceptance from this document or earlier engineering recommendations.
4. Record the final source/artifact identities, results and owner disposition in the release receipt. Until then, SR-01 and release approval remain open.

Source references: `pdf-ipc/.../jail/OperationScratchBroker.kt`, `pdf-ipc/.../jail/HostOutputTransaction.kt`, `pdf-ipc/.../security/WorkerGate.kt`, `pdf-ipc/src/main/aidl/.../IPdfJailService.aidl`, `app-host/.../logic/PdfGateway.kt`, `app-host/.../sandbox/NativeRendererCoordinator.kt`, and `pdf-renderer/.../engines/NativeRenderWorker.kt`.
