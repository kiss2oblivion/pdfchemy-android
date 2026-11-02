# SR-01: host-enforced worker storage boundary

Base: `7b939c319040290cee18a633eb2a6edb3919af46`.
Branch: `fix/2.1.0-worker-storage-boundary`. Independent review batch, 2026-10-25.

## Root cause and implementation

The host previously supplied writable anonymous regular files for worker output,
scratch and renderer pixels. Native code holding those descriptors could bypass
cooperative byte counters. Checking file lengths after execution did not bound
allocation while a hostile worker was running.

Producers now receive write-only pipes. A host collector polls them and charges
quotas before writing into private, mode0600, unlinked backing files. The limits
are 250 MiB aggregate output, 250 MiB scratch, 500 MiB per operation, and 500 MiB
across all active collectors, including collectors still cleaning up. Global
backing-file cardinality is bounded at 96; existing per-operation limits remain
32 outputs and 64 scratch files. Constructor overrides can only lower limits.
Each collector uses one drain thread rather than a thread per descriptor.

Scratch readback closes the write capability and waits for actual pipe EOF. The
host then returns a seekable read-only snapshot. JailScratch atomically replaces
the original descriptor number with a read-only placeholder and then the completed
snapshot, preserving existing capability paths without an FD-reuse window.
Archives and native PDF rendering can still seek completed scratch data.

The host retains its writable backing handle and truncates payloads on cleanup.
Retained read-only worker duplicates therefore cannot pin those payloads after
the operation closes. Reservations are released only after cleanup; a truncation
failure poisons future collectors. Cancellation-safe ownership covers snapshots
and decoded bitmaps crossing coroutine dispatchers.

PdfGateway and the legacy client share the scratch/output collector and release
host writer copies after Binder dispatch. Output validation waits for all writers
to close. Existing typed response validation, private publication snapshots,
acceptance handshake, publication quotas, source staging and worker admission
remain in place. Worker byte counts provide metadata only; host-observed bytes
control storage and publication. Renderer pixel and JPEG writes use this same
boundary; the renderer receives no writable host destination descriptor.

## Exact changed files in this batch

- `app-host/src/main/java/com/pdfchemy/app/logic/PdfGateway.kt`
- `app-host/src/main/java/com/pdfchemy/app/sandbox/NativeRendererCoordinator.kt`
- `pdf-ipc/src/main/aidl/com/pdfchemy/app/jail/IScratchBroker.aidl`
- `pdf-ipc/src/main/java/com/pdfchemy/app/jail/BoundedWorkerStorage.kt`
- `pdf-ipc/src/main/java/com/pdfchemy/app/jail/HostOutputTransaction.kt`
- `pdf-ipc/src/main/java/com/pdfchemy/app/jail/OperationScratchBroker.kt`
- `pdf-ipc/src/main/java/com/pdfchemy/app/jail/PdfJailClient.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/CapabilityIo.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/FdZipFile.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/JailOutput.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/JailScratch.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/PdfJailService.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/JailCompressionWorker.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/OfficeExportEngineWorker.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfServiceEngineWorker.kt`
- `pdf-ipc/src/test/java/com/pdfchemy/app/jail/WorkerStorageQuotaTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/WorkerStorageBoundarySecurityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/WorkerStorageAllocationFailureSecurityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/RendererStorageBoundarySecurityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/HostOutputCommitSecurityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/logic/PdfToEpubEngineTest.kt`
- `reports/worker-storage-boundary-2026-10-25/SR01_EXECUTION.md`

The three engine edits only replace regular-file size metadata with tracked
output metadata needed by the pipe transport. The independent redaction engine,
Gemini-owned files and shared release-status documents are unchanged.

## Regression verification

All 439 unit tests pass: 61 IPC, 94 worker and 284 host, with zero failures,
errors or skips. Seven new quota tests cover aggregate categories, overflow,
lower-only configuration and global byte/file bounds without allocating large
files.

The initial integrated focused native run passes all 45 methods on the owned offline API36
emulator, with zero failures/errors/skips/crashes and unchanged source hashes
throughout the run. Its receipt binds all 19 changed source/test files and both
APKs. Ten collector tests include raw seek/truncate rejection, read-only seekable
readback, payload reclamation despite retained descriptors, aggregate limits,
deadline, cancellation and scratch cardinality. Actual isolated-UID Binder probes
verify that raw Os.write bypassing cooperative accounting hits a 32-KiB host cap
and never opens the destination, and that a readback descriptor cannot be written
or reopened for writing through /proc/self/fd.

Three renderer tests verify bitmap and JPEG readback and preservation of an
existing destination on rendering failure. Thirteen output-transaction tests
retain exactly-once publication, malformed-response rejection, admission/BUSY,
worker death/cancellation, the real 120-second deadline, legacy writers and real
multi-output operations. A retained writer must time out before any destination
opens; a retained completed readback descriptor cannot mutate published bytes.

All eleven existing Editor point/crop/rotation/redaction tests and eight
large-page tests pass through the changed transport. These reopen and render the
actual exported PDFs, including text/raw-byte/decoded-object-stream privacy,
300 x 200 points, A4/Letter, crop offsets, quarter-turn rotations, alignment and
fail-closed publication.

Raw artifacts are outside Git under the validation root:
`sr01-native-integration-final/`, `sr01-local-verification/`,
`sr01-final-focused-build.log`, and `sr01-full-validation.log`.

The initial collector proof passed six methods and failed one overly strict
st_blocks==0 assertion after successful truncation. The corrected test compares
post-truncate allocation with a fresh empty inode, retaining the original failed
attempt. A subsequent collector proof passed all seven methods. The first
integration run passed all 38 test methods, but its final receipt checker rejected
source changes made while the next probes were being added. That run is retained
as an incomplete verification envelope. The final 45-method run passes both the
tests and the unchanged-source receipt check.

Final source review then identified an allocation-failure cleanup edge in
`20ba6b91`: shared storage was created before the request's try/finally, so a
partially constructed output transaction could leave its collector alive until
the deadline. A new bounded native probe occupies 95 empty descriptor slots,
allows the request to create its first output, then rejects its second output.
On the initial implementation the next collector fails because the first slot
is still held. That exact baseline failure is retained under
`sr01-native-allocation-baseline/`, with a byte-identical production APK to the
earlier 45-method run. The fixture allocates no payload and never binds a worker.

PdfGateway and the legacy client now close their shared scratch/storage owner
if transaction construction throws, preserving the original exception and
attaching any cleanup error. The new method also requires both destinations to
remain untouched. Subsequent verification for this followup must supersede the
earlier source/artifact identities rather than treating the earlier pass as a
current-head receipt.

The first four-API CI run, [40204805131](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40204805131),
then failed PDF-to-EPUB conversion on API24 (307 of 308 methods passed). The
service created a duplicated output stream without closing it; the converter
correctly leaves its caller-owned output stream open. A pipe therefore could not
reach EOF until garbage collection closed that duplicate. Both PDF-to-EPUB and
EPUB-to-PDF dispatch now scope their input/output capabilities with use, including
exception paths. The existing EPUB test reports the underlying exception on
failure; a new actual PDF-to-EPUB-to-PDF round trip checks both pipelines and
retained chapter text. The independent redaction engine is unchanged.

All three targeted followup native methods pass on the owned offline API36
emulator with unchanged source identities: the allocation-pressure regression,
the existing compliant EPUB-package test and the new round trip. Evidence is in
`sr01-native-transport-cleanup-targeted/`; the initial API24 job log is retained
as `sr01-initial-api24-job.log`. Full final-source native/CI and signed receipts
are post-commit gates, rather than inferred from earlier source results.

The followup also passes all 439 unit tests, security audit and architecture,
debug app/test assembly, release lint (zero errors, 640 warnings), and diff
checks. The source-bound local receipt is
`sr01-transport-cleanup-targeted-local-verification/receipt.json`.

Security audit and architecture checks pass. Release lint has zero errors and
639 warnings. Debug app/test assembly and git diff checks pass. These results are
bound in `sr01-local-verification/receipt.json`. Four-API CI and fresh
signed/minified verification are subsequent post-commit gates; this local debug
evidence alone does not establish them.

## Limits of the claim

The byte limits bound worker-induced backing payloads, not exact filesystem
metadata, journal/block-rounding overhead or the app's entire disk usage. Empty
inodes held by hostile read-only duplicates can outlive payload truncation.
Host-only commit copies, admitted input staging and ordinary app storage remain
separate resources. Existing publication quotas are preserved.

These are controlled syscall probes under an assumed hostile worker, not evidence
of an actual sandbox compromise. No real-volume exhaustion was attempted, no
worker-termination guarantee is inferred from a cooperative timer, and no owner
risk acceptance or release approval is inferred. Physical-device and printer
checks remain pending at the user's instruction. No merge is authorized by this
report.

The API24-compatible primitives are documented by Android's
[ParcelFileDescriptor.createPipe](https://developer.android.com/reference/android/os/ParcelFileDescriptor#createPipe())
and [Os.poll](https://developer.android.com/reference/android/system/Os#poll(android.system.StructPollfd[],%20int)).
The handoff and cleanup depend on kernel
[pipe EOF semantics](https://man7.org/linux/man-pages/man7/pipe.7.html) and
[truncate semantics](https://man7.org/linux/man-pages/man2/truncate.2.html).
