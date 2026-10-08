# Current UI/UX implementation reconciliation

Reviewed 7 October 2026 against fetched `repo-android/main` at
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
| UX-03 | PARTIAL | Batch 1 removes duplicate Quick Fill; remaining category/scan ambiguity is still open. |
| UX-04 | DONE | Batch 2: safeguard source is carried in a URI-bearing Split route, restored through ScreenSaver, and shown as selected input. |
| UX-05 | PARTIAL | MainViewModel activeJob cancellation and pending-output cleanup exist; shared Processing often has no task ID, OCR lacks real page feedback. Complete truthful task progress and scoped cancellation. |
| UX-06 | PARTIAL | MainActivity result has Open/Share/Another/Home; several tools call showSuccessToast without output URI, Open swallows failure, no supported Files action. |
| UX-07 | STILL OPEN | `logic/FileUtil.kt` uses URI lastPathSegment; editor/organizer and other tools have generic suggestions. Resolve display names and preserve editable suffixes. |
| UX-08 | STILL OPEN | Settings has no preferred output destination. Add local persisted SAF directory with change/reset and revoked-grant fallback. |
| UX-09 | STILL OPEN | CreateDocument/provider still owns collision handling. Explicit safe-copy/confirmed overwrite policy needed for app-managed destinations. |
| UX-10 | STILL OPEN | `OrganizeScreens.kt` Split/Delete/Rotate retain numerical range controls; visual workspace exists separately. Add content-based selection and retain input on failure. |
| UX-11 | DONE | `OrganizerSession.kt`, `PageOrganizerScreen.kt`: session undo/redo/reset and tests exist. Do not replace. |
| UX-12 | DONE | Organizer initializes every page before rendering; failed previews retain originalIndex and toPageActions exports all identities. Existing failure tests. |
| UX-13 | DONE | Batch 2: direct validated Move To position uses one chronological undo step and preserves all page identities. |
| UX-14 | PARTIAL | Organizer publishes previews incrementally; still serial eager render and bitmap retention without bounded cache. Measure and bound memory; preserve all page identities. |
| UX-15 | DONE | Batch 5 wires native nested TOC, page jump, lazy thumbnails, local bookmarks, fit-page/width modes, share and print alongside existing gestures/search. Physical gesture and printer-service checks remain deferred until a device/service is available. |
| UX-16 | DONE | Batch 5 releases hidden native chrome padding and adds reflow hide/reveal controls, including a full reading viewport when tabletop controls are hidden. |
| UX-17 | DONE | Batch 6 retains EditorSession in the ViewModel across configuration recreation, protects input replacement and Back, preserves annotations at a saved baseline, and handles save cancellation/continue/discard explicitly. Process-death and protected-device recovery validation remain unverified; no new persistent sensitive draft store was introduced. |
| UX-18 | DONE | Batch 5 preserves the original Reader route and in-memory page/offset through tool callbacks; Back/success returns to that Reader instead of a category. Batch 4 already corrected initial signing handoff rendering. |
| UX-19 | DONE | Editor and QuickFill use chronological EditorSession with undo/redo, rotation/redaction tracking and mixed-action tests. |
| UX-20 | DONE | Batch 6 routes narrow, dual-page and tabletop layouts through the same page-specific interactive annotation surface and rotation plane. Physical foldable posture checks remain deferred until hardware is available. |
| UX-21 | PARTIAL | Worker mixed-annotation/redaction ordering is preserved and Batch 6 shares annotation paints with preview. The native pixel test is compiled, but mixed-page PDF/export/existing-rotation corpus execution remains deferred until an Android test device/emulator is available; Windows Robolectric lacks its native runtime DLL. |
| UX-22 | DONE | Batch 6 removes fixed preview sp/dp annotation sizing and duplicate export painters. Both use AnnotationRenderer in normalized page coordinates with the same scale, baseline, alpha and stamp geometry. |
| UX-23 | PARTIAL | Batch 2: renderUriToBitmap staging, decode and cleanup now stay on IO; physical release-like latency/memory measurements remain open. |
| UX-24 | DONE | Reflow computes hasReadableContent and offers OCR for scanned/no-readable-content state; original empty-section gate fixed. |
| UX-25 | DONE | Batches 5–6 preserve original Reader return identity/offset, retained staged display names, ephemeral editor page across recreation, and editor persistent page only when the existing remember-position preference is enabled. |
| UX-26 | DONE | Batch 3: explicit local theme/font/serif defaults persist, with scoped reset and recreation/reset tests. |
| UX-27 | DONE | Batch 5 counts every search occurrence with paragraph/character identity, focuses the active glyph, and retains nested TOC depth and exact page targets. |
| UX-28 | DONE | Batches 5–6 distinguish loading, readable content and provider/load failure in Reader/editor, and expose retry/select-file rather than flashing inaccessible content during initial staging. |
| UX-29 | PARTIAL | Batch 5 separates Reader provider failures, password protection and damaged parsing from executable-threat copy. Shared/editor recovery presentation remains for the next batch; Vanguard engine remediation is preserved. |
| UX-30 | STILL OPEN | MainViewModel and tool screens pass exception messages into UiState.Error/toasts; technical details remain visible. Central meaningful error presentation with separate diagnostics. |
| UX-31 | STILL OPEN | EbookConverterScreen still uses rememberVanguardPdfPicker for EPUB/CBZ. Format-appropriate guarded selection needed; retain all PDF/security boundaries. |
| UX-32 | DONE | PdfManipulator.splitPdf requires readable pages and nonempty selected pages before creating outputs; release regression covers invalid split. |
| UX-33 | DONE | Batch 2: repeated gallery/camera URIs are deduplicated before URI-keyed image composition. |
| UX-34 | STILL OPEN | Compression/redaction still expose large technical settings inline. Separate Advanced with preserved state and useful defaults. |
| UX-35 | DONE | Batch 4: analysis generations reject obsolete results; per-input explicit choices and saved My default protect each setting independently; explicit save/reset controls and a delayed-analysis view-model regression verify the contract. |
| UX-36 | PARTIAL | Redaction review dialog lists counts/pages, not visual target review; smart patterns bypass visual preview. Add preview and explicit commit boundary. |
| UX-37 | DONE | Batch 4: changing query/input clears targets; request identity rejects out-of-order publication, failures have visible retry guidance, and only current query results can be used. |
| UX-38 | DONE | Batch 4 guards latest signing page publication and initial handoff; Batch 6 adds bounded chronological placement undo/redo and single-step drag history. Existing PKI error handling remains. |
| UX-39 | DONE | Repair clears diagnostic on picker and selected URI effect, displays diagnosis failure. Original stale report defect fixed. |
| UX-40 | STILL OPEN | HistoryRepository retains 20, Home shows five, no full-history/per-item removal/empty action. Preserve opt-in privacy and tool-first dashboard. |
| UX-41 | STILL OPEN | HistoryItem has no MIME/directory identity; generic labels and unfiltered Merge recents remain. Store actual identity, task-filter and check availability. |
| UX-42 | DONE | Batch 3: Settings/Premium system Back invokes the same callback as toolbar Back. |
| UX-43 | DONE | Batch 3: existing haptics preference gates the shared Compose feedback delegate; category/tool cards use that delegate. |
| UX-44 | DONE | Batch 3: Home exposes explicit System/Light/Dark selected choices and announces current mode. |
| UX-45 | DONE | First-run agreement has decline/use-locally path and persisted AdConsentGate. Do not redesign consent/security. |
| UX-46 | DONE | Banner eligibility explicitly excludes PdfReader/ReflowReader. Preserve exclusion; actual consented ad geometry is still a validation gate. |
| UX-47 | STILL OPEN | Billing launch silently returns without ProductDetails; CTA enabled and no visible failure/retry. Preserve entitlement logic and price decision. |
| UX-48 | PARTIAL | Batch 3: category height scales with font size; enlarged labels wrap and tool height is flexible. 320dp/font2.0 runtime verification remains open. |
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
O-03's deliberate price stays unchanged. No approved implementation item is
deferred by this document. Every non-DONE row remains scheduled work.

No merge or audit-complete claim is authorized until all remaining work has
been completed or explicitly deferred with a concrete justification.

## Current disposition overrides after implementation

The baseline evidence above is retained for traceability. Batch reports describe
changes and validation, and these overrides determine the current disposition:

| ID | Current disposition | Evidence |
|---|---|---|
| UX-01 | DONE | Batch 1: complete registry, distinct conversion/OCR/extraction routes, conceptual-query and coverage tests. |
| UX-02 | DONE | Batch 1: guarded direct Home Open PDF and safe URI-free Reader search entry. |
| UX-03 | PARTIAL | Batch 1 removes duplicate Quick Fill; remaining category/scan ambiguity is still open. |
| UX-04 | DONE | Batch 2: safeguard source is carried in a URI-bearing Split route, restored through ScreenSaver, and shown as selected input. |
| UX-13 | DONE | Batch 2: direct validated Move To position uses one chronological undo step and preserves all page identities. |
| UX-33 | DONE | Batch 2: repeated gallery/camera URIs are deduplicated before URI-keyed image composition. |
| UX-23 | PARTIAL | Batch 2: renderUriToBitmap staging, decode and cleanup now stay on IO; physical release-like latency/memory measurements remain open. |
| UX-26 | DONE | Batch 3: explicit local theme/font/serif defaults persist, with scoped reset and recreation/reset tests. |
| UX-42 | DONE | Batch 3: Settings/Premium system Back invokes the same callback as toolbar Back. |
| UX-43 | DONE | Batch 3: existing haptics preference gates the shared Compose feedback delegate; category/tool cards use that delegate. |
| UX-44 | DONE | Batch 3: Home exposes explicit System/Light/Dark selected choices and announces current mode. |
| UX-48 | PARTIAL | Batch 3: category height scales with font size; enlarged labels wrap and tool height is flexible. 320dp/font2.0 runtime verification remains open. |
| UX-49 | DONE | Batch 3: editor color/reflow theme choices expose names, selected radio semantics and 48dp touch bounds in both reflow layouts; physical TalkBack validation remains a sign-off gate. |
| UX-50 | DONE | Batch 3: the verification badge is an explicitly nonformatted single-percent string in all locale resources. |
| UX-35 | DONE | Batch 4: analysis generations reject obsolete results; per-input explicit choices and saved My default protect each setting independently; explicit save/reset controls and a delayed-analysis view-model regression verify the contract. |
| UX-37 | DONE | Batch 4: changing query/input clears targets; request identity rejects out-of-order publication, failures have visible retry guidance, and only current query results can be used. |
| UX-38 | PARTIAL | Batch 4: signing publishes count/bitmap only for the current URI/page/request and recycles unpublished results; initial Reader handoff now renders. Signature-session undo remains open. |
| UX-18 | PARTIAL | Batch 4 also repairs initial Sign handoff rendering; preserved Reader return and the remaining valid handoffs are still open. |
