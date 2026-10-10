# PDFchemy Android security code re-audit — 2026-10-09

## Scope and evidence

Frozen source: `kiss2oblivion/pdfchemy-android` at `80ecd0ba00d209daee14ce6613820b7b715c608c` (implementation branch). Android main remains `4b3c157f3458f7df00ea94044258d33c7449ced9`. The GitHub tree inventory returned 1,192 entries with `truncated=false`. This is a source review of Android trust boundaries and selected implementations, not proof that every line or every reachable runtime path is safe.

Local shell/edit calls stopped returning. Repository source was therefore read through GitHub at the fixed SHA. No new exploit, native suite, dependency inventory/OSV scan, secret scan, release artifact, build or lint result is claimed. Prior green CI is historical evidence and does not cover the findings below. The earlier startup patch request could not be verified locally and has not been published as a verified fix. This report does not certify release readiness.

Threat model: malicious documents/providers and external intents; compromised isolated PDF/native workers; sensitive document disclosure through screenshots/exports; user destination integrity. Ordinary apps are not assumed to possess root or to inject directly into private BillingClient callbacks.

## Findings

| ID | Severity | Disposition | Evidence confidence | Consequence |
|---|---|---|---|---|
| SA-01 | High / P1 | NEW — OPEN | High, source; provider reproduction pending | Cleanup may delete a pre-existing destination even before output publication |
| SA-02 | High / P1 | NEW — OPEN | High, source; native transition test pending | Secure-to-secure navigation can remove FLAG_SECURE from the active document screen |
| SA-03 | Medium / P2 | Reopened lifecycle gap under SEC-004/005 — OPEN | High for retention, runtime exhaustion pending | Retained staged inputs exhaust the process-wide 64-document/250-MiB budget |
| SR-01 | Conditional high availability risk | KNOWN residual under SEC-009 | Explicitly documented previously; source still matches | Compromised worker can bypass cooperative disk-write limits through transferred writable FDs |

Severity describes the consequence under each stated prerequisite. No source-only item is marked as a successfully reproduced exploit.

### SA-01 — Destination freshness is not tracked before deletion

Evidence: `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt` lines 64–83, 95–105 and 392–396. `addPendingOutputUri` stores a URI without provenance. `cancelOperation` waits for its job and then deletes all stored URIs. Publishing Error also deletes all stored URIs. `compressPdf` unconditionally registers its destination.

The worker gateway keeps provider destinations private and delays opening them until successful validation. That protection is defeated when UI cleanup later deletes an existing destination. A system save picker/provider may return an existing destination for explicitly confirmed overwrite; a bare URI does not establish that the app created a new document.

Audio shares this assumption: `AudioOutputPublisher.kt:17–18` deletes an arbitrary destination on copy failure, while `AudioExportService.kt:77–90` can delete a destination during rejected/failed job initialization before publication.

Precondition: the save flow supplies an existing writable/deletable destination, or a provider returns such a capability. This review does not claim every provider behaves that way.

Reproduction required: seed destination bytes; supply that same URI through a provider supporting overwrite; cause PDF failure/cancellation before host commit or audio failure before publication; assert that both the document and original bytes remain. Also prove that a newly created incomplete destination is removed.

Fix: represent output ownership explicitly: fresh app-created copy versus existing/unknown destination. Delete only proven fresh incomplete documents. Preserve existing bytes before publication. Generic SAF failure after publication begins may leave partial content; keep that existing limitation explicit rather than promising universal rollback. Do not weaken host validation/private snapshot boundaries.

### SA-02 — FLAG_SECURE is managed as independent toggles on a shared window

Evidence: `app-host/src/main/java/com/pdfchemy/app/ui/SecureScreen.kt:12–17` adds the activity window flag on composition and unconditionally clears it on disposal. Reader and Editor both call this helper. `MainActivity.kt:778–811` uses AnimatedContent with overlapping incoming/outgoing screen compositions.

Sequence: Reader owns secure flag; incoming Editor adds the same flag; outgoing Reader disposes and clears it. Editor remains composed, so its effect does not run again to restore protection. The resulting active document window can permit screenshots, recordings or task snapshots.

Fix: use one activity/window policy or window-scoped reference-counted secure leases. Preserve any independently existing secure flag; release only this policy's own ownership. Include rapid transitions and overlapping consumers, not just single-screen add/remove tests.

Reproduction required on API 24/30/34/36: Reader -> Editor, Editor -> another sensitive screen, rapid Back, recreation, and overlapping composition. Check FLAG_SECURE during and after transition. Physical screen-capture verification remains an additional gate.

### SA-03 — Staged capabilities outlive closed document sessions

Evidence: Reader load stages at `PdfReaderScreen.kt:159` without scoped release on exit. Successful picker handoff retains the staged capability. `DocumentStager.kt:16–19,44–57` tracks snapshots against global quotas; `SecurityLimits.kt:9,16` defines 250 MiB and 64 documents. `MainActivity.kt:306` only globally releases when the activity is finishing. Back/Home navigation does not finish it.

Distinct document opens and retries can accumulate snapshots even though no operation needs them. Externally delivered supported content URIs also reach the incoming-document flow. Cancellation cleanup exists for partial staging, but does not release every successfully staged obsolete session.

Fix: establish lifetime ownership across Reader, Editor, continuity tools and active operation leases. Release superseded/closed session inputs only after live borrowers finish. Retain no unbounded URI/position/cache history. Do not increase quotas or globally delete live stages.

Reproduction required: many distinct documents and repeated retries in one activity; quota occupancy must return to a bounded live-session baseline, and Reader -> tool -> Reader must continue to work.

### SR-01 — Hostile-worker disk allocation remains a known residual risk

`OperationScratchBroker` limits FD count and checks bytes at verification. `HostOutputTransaction` validates sizes and copies private snapshots before publication. These are strong publication boundaries. They do not revoke a retained worker FD or impose a kernel-enforced allocation limit while a compromised worker writes directly.

This was already explicitly documented in `SECURITY_REMEDIATION_STATUS.md`, Review Chunk 1. It is not a newly discovered regression or evidence of provider destination exposure. Preconditions include worker compromise or bypass of cooperative wrappers; a malicious PDF achieving such compromise was not demonstrated here.

Plan a separate feasibility decision: storage capacity admission and monitoring can reduce exposure but are not kernel quotas. Stronger solutions require enforcing allocation independently of the hostile process. Do not label a periodic fstat check as a hard quota or remove isolation to simplify storage.

## Other observations and verification gaps

- Non-forensic redaction deliberately appends visual rectangles and skips raster destruction. Its Advanced description explains rasterization, and secure mode defaults on. Treat stronger opt-out confirmation/naming as a hardening item; this is not proof that the default secure path retains the selected text.
- Search-generated redaction rectangles use positional text coordinates; native end-to-end search -> review -> save tests are still needed for crop origins, rotated pages and multline text. Manual annotation parity tests alone cannot establish the security of these targets.
- BillingManager checks PURCHASED/product identity and stores the result in encrypted preferences, but this reviewed class establishes no independent purchase-token verification and does not check/retry acknowledgment results. The former is revenue hardening under client compromise, not an ordinary-app entitlement injection exploit. Acknowledgment reliability is a separate concrete purchase-flow concern. Follow [Google Play verification guidance](https://developer.android.com/google/play/billing/security) while preserving the offline product model.
- Incoming file URI validation and whole-repository privacy-options wiring need follow-up. No automatic arbitrary-private-file exfiltration or missing-consent exploit was established; do not report either as verified.
- Native TTS is an installed service trust boundary. Audio selects non-network voices and the export service is private; this review did not verify the privacy behavior of third-party voice engines.
- Re-run the resolved dependency scanner and reachable-history secret gate. Declared versions/public Firebase identifiers are not sufficient evidence either of a vulnerability or of current safety.

## Controls preserved in inspected source

Both PDF workers are private isolated services. Host engines dispatch through staged immutable inputs and exact hash/size validation. Native renderer has single-flight execution, a 30-second watchdog and raster allocation bounds. PDF jail uses tokenized admission, host ACK, death handling and independent watchdogs. Real SAF output URIs remain host-owned; worker output is unlinked temporary storage; result validation and private snapshots precede destination publication. PixelWire bounds dimensions before bitmap allocation and does not run an encoded-image decoder in the host. Request/result validation includes byte, item, depth and node budgets. Archive spooling bounds entries and expanded bytes. Active-content scrubber bounds graph traversal and separates passive links/attachments from executable actions. Sanitization re-audits serialized output. Secure redaction aborts when native rasterization is unavailable. FileProvider exposes only scan/export subdirectories, is private, and uses explicit grants. Backups are disabled. Release logging is gated by BuildConfig.DEBUG. Wrapper checksum and CI action SHAs are pinned.

These are source observations, not newly executed acceptance results.

## Reviewed paths

Key source areas: MainActivity; MainViewModel; Android manifests; PreferredDocumentCreator/OutputPolicy from the preceding local review; PdfReaderScreen/VanguardPicker/RedactionScreen; SecureScreen; PdfGateway/PdfJailClient; HostOutputTransaction/WorkerResponseValidator/OperationScratchBroker; DocumentStager/StagedIdentity/SecurityLimits/WorkerGate/PixelWire; PdfJailService/RequestValidator/CapabilityIo/FdZipFile; PdfNativeRendererService/NativeRenderWorker; ActiveContentScrubber/PdfSanitizerEngineWorker/PdfRedactionEngineWorker; SignatureEngineWorker/TextFormatConverter/ComicBookEngineWorker; audio service/staging/publication; BillingManager/AdManager; AppLogger; file_paths; Gradle declarations/wrapper and Android CI workflow. Selected staging, ownership and output-commit test source and the prior remediation record were also inspected.

Website/Firebase backend rules, cloud console configuration, account credentials, production signing infrastructure and third-party native implementations were outside this Android review. No audit-complete or merge authorization is inferred.
