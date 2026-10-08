# Current UI/UX implementation reconciliation

Reconciled 7–8 October 2026 against fetched `repo-android/main` at
`4b3c157f3458f7df00ea94044258d33c7449ced9`. This supersedes the October 4
baseline matrix for implementation decisions; the original audit remains intact.
The existing Shrinkpdf working tree contains uncommitted work and was excluded.
Implementation proceeds in the isolated `codex/ux-audit-resume-2026-10-07` worktree.
No release certification or security audit is part of this track.

DONE means the original source defect is addressed, not that physical-device
validation has been performed. PARTIAL retains every unmet part of the original
finding. Device, provider, ad-load, and export-parity checks remain sign-off gates.

| ID | Current disposition | Current source evidence and remaining work |
|---|---|---|
| UX-01 | DONE | Batch 1: complete registry, distinct conversion/OCR/extraction routes, conceptual-query and coverage tests. |
| UX-02 | DONE | Batch 1: guarded direct Home Open PDF and safe URI-free Reader search entry. |
| UX-03 | DONE | Batch 1 removed duplicate Quick Fill; Batch 7 aligns Gray/Linearize Back with their Compression category, distinguishes Quick camera PDF from the document-scanning studio, and gives native Reader an accurate description. |
| UX-04 | DONE | Batch 2: safeguard source is carried in a URI-bearing Split route, restored through ScreenSaver, and shown as selected input. |
| UX-05 | DONE | Batch 9 reports actual isolated-worker completed pages, then a separate saving phase; validates operation token, quotas and event order, closes telemetry at terminal/cancellation, and rejects obsolete ViewModel callbacks. Native two-page searchable output and cancellation preserving destination bytes pass on Android 24/36. Batch 8 task-aware modal feedback and completed-file counts remain. |
| UX-06 | DONE | Batch 7 carries explicit output URIs from file-producing tools, lists names/MIME and individual Open actions, adds Browse files, and makes viewer/share failure visible. State-only Markdown import correctly creates no output. |
| UX-07 | DONE | Batch 7 resolves DISPLAY_NAME and retained staged aliases before generating editable action suffixes, sanitizes suggestions and verifies opaque-ID/provider-name behavior. |
| UX-08 | DONE | Batch 7 adds an explicit local SAF output-folder choice/change/reset. Existing save callbacks receive a fresh URI in that folder; revoked/unwritable access falls back to the system save picker. |
| UX-09 | DONE | Batch 7 states and enforces keep-both numbered copy behavior for app-managed destinations, rejects a provider returning an existing capability, and preserves the system picker's own collision confirmation for other locations. |
| UX-10 | DONE | Batch 8 adds content-based lazy visual selection to Split/Delete/Rotate, preserves typed ranges and source/range after errors, and removes premature Rotate navigation. Range round-trip/bounds regressions pass. |
| UX-11 | DONE | `OrganizerSession.kt`, `PageOrganizerScreen.kt`: session undo/redo/reset and tests exist. Do not replace. |
| UX-12 | DONE | Organizer initializes every page before rendering; failed previews retain originalIndex and toPageActions exports all identities. Existing failure tests. |
| UX-13 | DONE | Batch 2: direct validated Move To position uses one chronological undo step and preserves all page identities. |
| UX-14 | DONE | Batch 8 retains all page identities, renders only visible/prefetched lazy compositions with two in-flight previews at the existing 240px resolution, recycles owned previews, exposes retry/load recovery, rejects obsolete input publication and excludes bitmaps from bounded 50-action undo history. A 1000-page identity/history test passes. Physical 100-500-page latency/memory profiling is explicitly deferred with UX-23; no guessed cache budget or OOM claim. |
| UX-15 | DONE | Batch 5 wires native nested TOC, page jump, lazy thumbnails, local bookmarks, fit-page/width modes, share and print alongside existing gestures/search. Physical gesture and printer-service checks remain deferred until a device/service is available. |
| UX-16 | DONE | Batch 5 releases hidden native chrome padding and adds reflow hide/reveal controls, including a full reading viewport when tabletop controls are hidden. |
| UX-17 | DONE | Batch 6 retains EditorSession in the ViewModel across configuration recreation, protects input replacement and Back, preserves annotations at a saved baseline, and handles save cancellation/continue/discard explicitly. Process-death and protected-device recovery validation remain unverified; no new persistent sensitive draft store was introduced. |
| UX-18 | DONE | Batch 5 preserves the original Reader route and in-memory page/offset through tool callbacks; Back/success returns to that Reader instead of a category. Batch 4 already corrected initial signing handoff rendering. |
| UX-19 | DONE | Editor and QuickFill use chronological EditorSession with undo/redo, rotation/redaction tracking and mixed-action tests. |
| UX-20 | DONE | Batch 6 routes narrow, dual-page and tabletop layouts through the same page-specific interactive annotation surface and rotation plane. Physical foldable posture checks remain deferred until hardware is available. |
| UX-21 | DONE | Batch 8 places overlays in the original page rotation plane before added rotation; raster redaction uses the shared opaque painter and recycles its base bitmap while retaining forensic overwrite/annotation removal. Android 36 end-to-end exports pass eight combinations of existing rotation 0/90/180/270 plus added 90, with drawings, text, stamps and redaction/no redaction; color centroids, page aspect ratios and opaque redaction corners match preview. |
| UX-22 | DONE | Batch 6 removes fixed preview sp/dp annotation sizing and duplicate export painters. Both use AnnotationRenderer in normalized page coordinates with the same scale, baseline, alpha and stamp geometry. |
| UX-23 | PARTIAL | Batches 2/6 move staging, decoding and overlay painting to IO; Batch 8 bounds visible thumbnail ownership and in-flight work. DEFERRED: physical release-like Reader/annotation/100-500-page memory and latency traces (also O-01/O-04) need representative hardware. Only Android 24/36 debug emulators are available; emulator test duration is not physical performance evidence. |
| UX-24 | DONE | Reflow computes hasReadableContent and offers OCR for scanned/no-readable-content state; original empty-section gate fixed. |
| UX-25 | DONE | Batches 5–6 preserve original Reader return identity/offset, retained staged display names, ephemeral editor page across recreation, and editor persistent page only when the existing remember-position preference is enabled. |
| UX-26 | DONE | Batch 3: explicit local theme/font/serif defaults persist, with scoped reset and recreation/reset tests. |
| UX-27 | DONE | Batch 5 counts every search occurrence with paragraph/character identity, focuses the active glyph, and retains nested TOC depth and exact page targets. |
| UX-28 | DONE | Batches 5–6 distinguish loading, readable content and provider/load failure in Reader/editor, and expose retry/select-file rather than flashing inaccessible content during initial staging. |
| UX-29 | DONE | Batch 8 routes incoming/Recent provider failures to access recovery and ParseFailed to damaged-copy/Repair guidance instead of executable-threat dialogs. Encrypted and executable outcomes remain distinct and fail closed; Vanguard engine remediation is unchanged. |
| UX-30 | DONE | Batch 8 centralizes password/storage/access/damaged/general recovery presentation, retains actionable viewer/share guidance, makes raw details optional diagnostics, and dismisses errors without clearing selected input. Recovery classification regressions pass. |
| UX-31 | DONE | Batch 7 selects and stages EPUB/CBZ archives through matching format contracts, then uses the existing bounded archive engines. PDF conversion inputs still use the existing Vanguard picker. |
| UX-32 | DONE | PdfManipulator.splitPdf requires readable pages and nonempty selected pages before creating outputs; release regression covers invalid split. |
| UX-33 | DONE | Batch 2: repeated gallery/camera URIs are deduplicated before URI-keyed image composition. |
| UX-34 | DONE | Batch 8 hides technical compression/redaction choices under explicit Advanced controls while retaining ViewModel/session values and existing forensic/default behavior. Basic quality, target search and pattern review remain accessible. |
| UX-35 | DONE | Batch 4: analysis generations reject obsolete results; per-input explicit choices and saved My default protect each setting independently; explicit save/reset controls and a delayed-analysis view-model regression verify the contract. |
| UX-36 | DONE | Batch 8 renders each affected page with fitted normalized target highlights before explicit Save. Smart patterns now find/review targets through the same search and save boundary; changed pattern choices invalidate old targets, and saving captures the reviewed source and target list. |
| UX-37 | DONE | Batch 4: changing query/input clears targets; request identity rejects out-of-order publication, failures have visible retry guidance, and only current query results can be used. |
| UX-38 | DONE | Batch 4 guards latest signing page publication and initial handoff; Batch 6 adds bounded chronological placement undo/redo and single-step drag history. Existing PKI error handling remains. |
| UX-39 | DONE | Repair clears diagnostic on picker and selected URI effect, displays diagnosis failure. Original stale report defect fixed. |
| UX-40 | DONE | Batch 7 adds complete bounded history, per-entry removal and disabled/empty guidance without enabling history automatically or changing retention/privacy. |
| UX-41 | DONE | Batch 7 stores actual filename, MIME and directory identity, checks availability, routes recents by MIME, and restricts Merge selection to available PDF entries. |
| UX-42 | DONE | Batch 3: Settings/Premium system Back invokes the same callback as toolbar Back. |
| UX-43 | DONE | Batch 3: existing haptics preference gates the shared Compose feedback delegate; category/tool cards use that delegate. |
| UX-44 | DONE | Batch 3: Home exposes explicit System/Light/Dark selected choices and announces current mode. |
| UX-45 | DONE | First-run agreement has decline/use-locally path and persisted AdConsentGate. Do not redesign consent/security. |
| UX-46 | DONE | Banner eligibility explicitly excludes PdfReader/ReflowReader. Preserve exclusion; actual consented ad geometry is still a validation gate. |
| UX-47 | DONE | Batch 7 disables checkout until Play/product readiness, provides connection/details timeout and retry, and shows cancelled/failed/pending outcomes. Mocked Play callback integration confirms no purchase launch or entitlement grant during readiness/retry/cancellation tests. |
| UX-48 | DONE | Batch 8 removes enlarged description line caps, makes Home card height flexible and tool content wrap, and provides a wrapping enlarged-search input. Android 36 at 320dp width/font2.0 passes rendered glyph-bound/no-ellipsis checks, scroll access and actual search-to-Reader selection; normal-size visual structure is preserved. |
| UX-49 | DONE | Batch 3: editor color/reflow theme choices expose names, selected radio semantics and 48dp touch bounds in both reflow layouts; physical TalkBack validation remains a sign-off gate. |
| UX-50 | DONE | Batch 3: the verification badge is an explicitly nonformatted single-percent string in all locale resources. |

No original finding is wholly SUPERSEDED: the dedicated Reader supersedes the
old proposed Reader architecture, but unmet behavior remains under PARTIAL.
Wave 2 Audio Export and merged Vanguard remediation are preserved dependencies.

## Sequence and sign-off

1. Discovery: complete existing search registry, correct destinations, guarded
   direct reading entry, and contained category ambiguity (UX-01/02/03).
2. Correctness: selection identity, async choices/results, session and export
   fidelity (UX-17/20/21/22/28/30/31/33/35/37/38/41).
3. Reader and workflow: remaining Reader controls/return continuity, reflow,
   visual page/redaction work, progress and output contracts.
4. Contained controls, accessibility and measured performance.

Physical phone/tablet/foldable profiling, real consented ads, TalkBack, provider
collision/grant failure and saved-copy inspection remain required evidence;
source review alone cannot certify those outcomes. O-01/O-04 require measured
profiling before optimization; O-02 needs active-work touch/Back verification;
O-03's deliberate price stays unchanged. UX-05 is complete in Batch 9 with actual worker page events, distinct saving feedback and cancellation regression evidence. Physical performance
profiling under UX-23 is explicitly deferred because only an emulator is available;
see UX_RUNTIME_VALIDATION_2026-10-08.md for scope and resumption conditions.

No merge or audit-complete claim is authorized until all remaining work has
been completed or explicitly deferred with a concrete justification.


The single 50-row matrix above is authoritative. Batch receipts preserve prior
implementation evidence without conflicting historical disposition overrides.
