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

| ID | Disposition at baseline | Current source evidence and remaining work |
|---|---|---|
| UX-01 | PARTIAL | `ui/ToolSearch.kt`: search exists, but only 12 entries; OCR and text extraction are conflated. Complete registry and conceptual queries. |
| UX-02 | PARTIAL | `MainActivity.kt`: external open routes to dedicated PdfReader; Home has no direct Read action. Add guarded picker entry, reuse Reader. |
| UX-03 | PARTIAL | Compression category includes Grayscale/Linearize; `OrganizeScreens.kt` duplicates Quick Fill and category scan names still overlap. Narrow naming/duplication correction. |
| UX-04 | PARTIAL | Split continuity was reported completed, but fetched main's `SplitPdfScreen` initializes input to null and safeguard route only sets Screen.SplitPdf. Verify/restore wiring; do not port uncommitted local edits blindly. |
| UX-05 | PARTIAL | MainViewModel activeJob cancellation and pending-output cleanup exist; shared Processing often has no task ID, OCR lacks real page feedback. Complete truthful task progress and scoped cancellation. |
| UX-06 | PARTIAL | MainActivity result has Open/Share/Another/Home; several tools call showSuccessToast without output URI, Open swallows failure, no supported Files action. |
| UX-07 | STILL OPEN | `logic/FileUtil.kt` uses URI lastPathSegment; editor/organizer and other tools have generic suggestions. Resolve display names and preserve editable suffixes. |
| UX-08 | STILL OPEN | Settings has no preferred output destination. Add local persisted SAF directory with change/reset and revoked-grant fallback. |
| UX-09 | STILL OPEN | CreateDocument/provider still owns collision handling. Explicit safe-copy/confirmed overwrite policy needed for app-managed destinations. |
| UX-10 | STILL OPEN | `OrganizeScreens.kt` Split/Delete/Rotate retain numerical range controls; visual workspace exists separately. Add content-based selection and retain input on failure. |
| UX-11 | DONE | `OrganizerSession.kt`, `PageOrganizerScreen.kt`: session undo/redo/reset and tests exist. Do not replace. |
| UX-12 | DONE | Organizer initializes every page before rendering; failed previews retain originalIndex and toPageActions exports all identities. Existing failure tests. |
| UX-13 | STILL OPEN | Organizer offers adjacent arrows, no direct distant move. Add Move To or direct reorder while retaining arrows. |
| UX-14 | PARTIAL | Organizer publishes previews incrementally; still serial eager render and bitmap retention without bounded cache. Measure and bound memory; preserve all page identities. |
| UX-15 | PARTIAL | Dedicated PdfReader has continuous reading, pinch/double-tap zoom/pan, search and outline. Missing jump/thumbnails, user bookmarks, share/print and reading modes; inspect gestures on devices. |
| UX-16 | PARTIAL | PdfReader chrome can hide; fixed 80/120dp content padding remains and reflow controls are permanent. Complete document-first behavior without replacing Reader. |
| UX-17 | PARTIAL | PdfEditor guards dirty Back; source change/session recreation and saved-copy boundary still need session preservation validation. |
| UX-18 | PARTIAL | Reader sets continuity URI for Compress/OCR/Sign/Protect and forwards Editor URI; tool Back returns categories, not preserved Reader. Extend valid handoffs and return session. |
| UX-19 | DONE | Editor and QuickFill use chronological EditorSession with undo/redo, rotation/redaction tracking and mixed-action tests. |
| UX-20 | STILL OPEN | PdfEditor wide/tabletop branches still display plain Image, unlike narrow interactive annotation surface. Preserve posture layout and share interactive surface. |
| UX-21 | PARTIAL | PdfEditorWorker now composites annotations before raster redaction and applies rotation. Source omission is fixed; mixed preview/export order and existing-rotation parity need validation. |
| UX-22 | PARTIAL | Text placement uses canvas ratios; preview still uses fixed sp text and dp stamp size while export scales annotations. Unify dimensions/transforms and verify density/rotation parity. |
| UX-23 | PARTIAL | renderPageToBitmap decodes inside IO; renderUriToBitmap ends with PixelWire.read on caller context, though native Reader wraps IO. Fix remaining UI caller path and profile. |
| UX-24 | DONE | Reflow computes hasReadableContent and offers OCR for scanned/no-readable-content state; original empty-section gate fixed. |
| UX-25 | PARTIAL | Native Reader has stable DocumentIdentity and enabled preference; Reflow uses incoming URI rather than random snapshot and same toggle. Editor and staged-tool-return identity/offset persistence remain. |
| UX-26 | STILL OPEN | Reflow initializes Light/16/Sans each entry. Persist explicit defaults with reset. |
| UX-27 | STILL OPEN | Reflow search returns section indices and outline drawer remains flat. Add occurrence-aware navigation and nested outline. |
| UX-28 | PARTIAL | Loading/render states exist; editor starts pageCount=0/rendering=false and native Reader can leave blank state during initial staging/failure. Explicit loading/ready/error needed. |
| UX-29 | PARTIAL | Merged Vanguard taxonomy distinguishes executable/encrypted/parse failure; do not redo remediation. DocumentLoadGuard/Reader still collapse provider/render errors into blocked/generic unsafe copy. Fix only UI recovery mapping. |
| UX-30 | STILL OPEN | MainViewModel and tool screens pass exception messages into UiState.Error/toasts; technical details remain visible. Central meaningful error presentation with separate diagnostics. |
| UX-31 | STILL OPEN | EbookConverterScreen still uses rememberVanguardPdfPicker for EPUB/CBZ. Format-appropriate guarded selection needed; retain all PDF/security boundaries. |
| UX-32 | DONE | PdfManipulator.splitPdf requires readable pages and nonempty selected pages before creating outputs; release regression covers invalid split. |
| UX-33 | STILL OPEN | CreateScreens appends repeated URIs and uses URI-only Lazy keys. Deduplicate selections or give occurrences stable unique identities. |
| UX-34 | STILL OPEN | Compression/redaction still expose large technical settings inline. Separate Advanced with preserved state and useful defaults. |
| UX-35 | STILL OPEN | MainViewModel async analysis overwrites quality/scenario toggles in both input paths; no deliberate recurring profile. Guard user choices and stale analysis; persist/reset explicit profile. |
| UX-36 | PARTIAL | Redaction review dialog lists counts/pages, not visual target review; smart patterns bypass visual preview. Add preview and explicit commit boundary. |
| UX-37 | PARTIAL | New search clears old boxes, but no visible failure or query/result association; concurrent requests can publish stale boxes. Guard request identity and expose retry. |
| UX-38 | PARTIAL | Signing clears old bitmap, shows render/PKI failures; render requests can race and signature edits lack session undo. Complete state association/history. |
| UX-39 | DONE | Repair clears diagnostic on picker and selected URI effect, displays diagnosis failure. Original stale report defect fixed. |
| UX-40 | STILL OPEN | HistoryRepository retains 20, Home shows five, no full-history/per-item removal/empty action. Preserve opt-in privacy and tool-first dashboard. |
| UX-41 | STILL OPEN | HistoryItem has no MIME/directory identity; generic labels and unfiltered Merge recents remain. Store actual identity, task-filter and check availability. |
| UX-42 | STILL OPEN | Settings and Premium lack their own BackHandler; toolbar Back differs from system Back. Align navigation. |
| UX-43 | STILL OPEN | CategoryCard/ToolCard directly perform haptics regardless of preference. Route through existing preference-aware feedback. |
| UX-44 | STILL OPEN | Home binary icon cycles System/Light/Dark with no current-state label. Expose explicit choices. |
| UX-45 | DONE | First-run agreement has decline/use-locally path and persisted AdConsentGate. Do not redesign consent/security. |
| UX-46 | DONE | Banner eligibility explicitly excludes PdfReader/ReflowReader. Preserve exclusion; actual consented ad geometry is still a validation gate. |
| UX-47 | STILL OPEN | Billing launch silently returns without ProductDetails; CTA enabled and no visible failure/retry. Preserve entitlement logic and price decision. |
| UX-48 | STILL OPEN | Home 150dp category slots and ToolCard 108dp height still truncate enlarged labels. Local adaptive height/layout correction and device validation. |
| UX-49 | STILL OPEN | Editor/reflow swatches lack named selectable semantics and adequate explicit touch bounds. Fix both posture variants. |
| UX-50 | STILL OPEN | values/strings.xml vanguard_zero_trust_badge still contains 100%%; inspect nonformatted resource/locale usage before correction. |

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
