# SR-01: Compromised-Worker Scratch-Allocation Residual Risk Assessment & Decision

**Document ID:** SEC-SR-01-2026-10-11  
**Project:** PDFchemy Tools (Android)  
**Security Boundary:** Host / Isolated PDF Worker Process (`OperationScratchBroker`)  
**Disposition:** Explicit Architectural Residual Risk Acceptance & Proportional Defense-in-Depth  

---

## 1. Threat Scenario & Mechanism

### 1.1 Threat Model
- **Adversary Capability:** Full arbitrary native code execution (RCE) achieved inside one of the two private isolated service worker processes (`PdfJailService` or `PdfNativeRendererService`) via a malformed PDF parser exploit.
- **Attack Vector:** An attacker-controlled worker attempts an availability denial-of-service (storage exhaustion) by writing excessive dummy bytes to open scratch file descriptors transferred from the host.

### 1.2 Mechanism & Limitations in Inspected Architecture
1. **Host Capabilities:** `OperationScratchBroker` creates anonymous, immediately-unlinked scratch temporary files in `context.cacheDir` (`File.createTempFile("scratch_", ".bin")`, followed by immediate `file.delete()`).
2. **Capability Transfer:** The host passes a duplicated `ParcelFileDescriptor` to the worker process across the Binder IPC boundary.
3. **Execution Reality:** In standard Linux/Android user-space, a process holding a writable file descriptor to a regular filesystem inode can issue `write()` or `fallocate()` calls up to available partition space. Cooperative byte-accounting wrappers inside worker code are ineffective against a fully compromised native worker.
4. **Enforcement Boundaries in Place:**
   - **FD Count Cap:** Strictly enforced by host before transfer (`SecurityLimits.MAX_SCRATCH_FDS = 64`).
   - **Pre-Publication Host Validation:** `HostOutputTransaction` validates all response descriptors against `SecurityLimits.MAX_OUTPUT_BYTES` (250 MiB) and runs full PDF structural sanity verification before any byte is copied to user destinations.
   - **Zero User Document Exposure:** The unlinked scratch files are anonymous capabilities; a worker has zero access to user destination URIs or host private storage paths.
   - **Process Lifecycle Reclamation:** Because scratch files are unlinked at allocation time (`unlink()`), all allocated disk space is automatically freed by the Linux kernel the moment the descriptors are closed or the worker process terminates.
   - **Host Watchdog Enforcement:** The host runs a hard 30-second (`RENDER_DEADLINE_MS`) and 120-second (`WORKER_DEADLINE_MS`) watchdog. If a worker hangs or flushes excessive writes, the host unbinds and terminates the worker.

---

## 2. Evaluation of Potential Mitigations

| Mitigation Strategy | Feasibility | Security Efficacy | Architectural Impact | Decision |
|---|---|---|---|---|
| **Kernel Filesystem Quotas (f2fs/ext4)** | Infeasible on Android | High | Android does not expose per-process filesystem disk quotas to ordinary non-root applications. | Rejected |
| **`setrlimit(RLIMIT_FSIZE)`** | Low (Bypassed under RCE) | Low | `setrlimit` must be called in the worker process. A compromised process with arbitrary code execution can call `setrlimit` again to lift the bound. | Rejected |
| **Host-Mediated Streaming (UNIX domain socket/pipe proxy)** | High Engineering Cost / Non-Seekable | High | PDF writers and format converters (MuPDF/PDFBox) require seekable random-access I/O (`lseek`/`pwrite`). Pipes do not support random access and would require substantial in-memory spooling or an unbounded redesign. | Rejected (violates core mandate) |
| **Host Pre-Allocation Admission & Post-Execution Verification** | High | Medium (Admission & Publication Boundary) | Already implemented (`verifyBudget()` and storage quotas); prevents host publication of oversized outputs. | Retained & Enforced |

---

## 3. Explicit Architectural Decision

### Decision: ACCEPT RESIDUAL RISK WITH ENFORCED HOST BOUNDARIES

1. **Proportionate Defense:** The primary objective of process isolation is preventing data exfiltration, memory compromise of host credentials/keys, and damage to user documents. That primary security boundary is 100% preserved.
2. **Availability Impact Bounded:** An attack can at worst temporarily fill cache partition space while the worker runs. The unlinked status guarantees instantaneous OS kernel recovery upon process death, and host watchdogs guarantee termination within 30–120 seconds.
3. **No Unbounded Redesign:** Converting random-access scratch storage to host-mediated stream proxies would introduce massive memory overhead, degrade performance on large documents, and destabilize isolated workers without eliminating other potential CPU/battery exhaustion vectors under native compromise.

---

## 4. Verification & Defense-in-Depth Commitments

1. Keep `SecurityLimits.MAX_SCRATCH_FDS` (64) and `SecurityLimits.MAX_OUTPUT_BYTES` (250 MiB) strictly enforced.
2. Maintain immediate file unlinking on scratch creation to guarantee kernel-level automatic space reclamation.
3. Retain mandatory host-side `verifyBudget()` and strict `HostOutputTransaction` verification prior to any destination publication.
