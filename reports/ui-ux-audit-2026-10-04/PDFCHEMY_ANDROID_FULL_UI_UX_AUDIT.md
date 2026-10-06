# PDFchemy Android — Full UI/UX Audit

Audit date: 4 October 2026. Audit-only: no production code, resources, layouts, behavior, or commits changed.

## Executive Summary

PDFchemy has a coherent visual baseline and a substantial, useful tool surface. Its dashboard, category cards, compact Premium placement, source-first tool entry screens, and Merge workspace deserve preservation. A wholesale visual redesign is not warranted.

The larger gap is between the app's power and the effort required to access and trust it. The current implementation exposes 57 category-card entries without tool search. Several operations end in a generic dialog rather than a useful result handoff. Advanced options compete with the basic compression workflow. Document state is not consistently carried between surfaces.

Reader is the highest-value intervention. External PDFs route into the PDF editor. Its View mode is a fitted page image with previous/next controls, not a mature reading experience: no PDF zoom/pan, continuous scrolling, PDF search, page jump, thumbnail navigation, or reading bookmarks. Editing controls remain visible. Leaving an edited document discards the session without a save/discard boundary. The separate reflow reader offers valuable text-reading capabilities but does not substitute for a full-layout PDF reader.

Several source-confirmed conditional branches deserve correctness tests before cosmetic work: failed organizer thumbnails can remove pages from the saved copy; redaction can skip other edits on the same exported page; wide Reader modes omit the interactive annotation layer; text/stamp coordinates disagree between preview and export. Runtime also reproduced a damaged, script-free fixture being falsely described as containing embedded scripts. These are not observed original-file destruction, and no P0 is established by this audit. The findings table contains 16 P1, 33 P2, one P3, and four separately labeled observations.

Highest-value direction: preserve the design, close document/session correctness gaps, provide deterministic tool search, make Reader document-first, and standardize progress, recovery, and output handoff. Do not add analytics, behavioral ranking, aggressive Premium promotion, or ornamental dashboard content.

### Scope, provenance, and evidence limits

The report combines Android source inspection, complete source-level journey traces, targeted emulator execution, UI hierarchy captures, screenshots where Android permits them, and official competitor/platform documentation. It is a full surface audit, not a claim that every operation has been executed on every device.

| Item | Audited baseline |
|---|---|
| Repository | `Shrinkpdf`, HEAD `de053371333a773157ad66838d726071e32d1cab` |
| Android app | PDFchemy 2.0.6, versionCode 13; min SDK 24, target/compile SDK 36 |
| Modules | `app-host`, `pdf-ipc`, `pdf-jail`, `pdf-renderer`; desktop UI excluded |
| Runtime | Existing debug APK, API 36 emulator `SecurityApi36`, software GPU `swiftshader_indirect` |
| APK identity | Installed APK matched local `app-host/build/outputs/apk/debug/app-host-debug.apk`; SHA-256 `D2447D43D1BB784254F437D504292990269E6579AE642D599CE85BA2C5C55E5B` |
| Build limitation | No rebuild/install was performed. Matching installed/local artifacts does not prove a reproducible build from this commit. Source and runtime claims are labeled separately. |
| Runtime harness | Existing DEBUG `isScreenshotRun` path bypasses first-run consent/onboarding, treats the session as Premium, suppresses ads, and injects an asset for compression selection. Real Android document pickers were exercised for Editor, Merge, and Organizer. |
| Document fixtures | Copies of existing feedback/production PDFs in emulator Downloads; seven-page feedback PDF used for Reader/Organizer. An intentionally truncated, script-free 96-byte PDF fixture tested recovery. Original workspace PDFs untouched. Compression used the harness's 120-page Annual Report asset. |
| Capture limitation | `FLAG_SECURE` produces black screenshots on protected document surfaces. XML hierarchy and code were used there. This is privacy protection, not evidence of failed rendering; it was not bypassed. |
| Device coverage | Approximately 411dp phone; simulated 320dp phone; font scale 1.0/2.0; actual landscape rotation; simulated 1707×1067dp large landscape display; light/dark dashboard. System display/font/rotation settings restored afterward. |
| Not runtime validated | Physical phone/tablet/foldable, low-memory kill/restart, real ad loading, Play billing, complete TalkBack traversal, reduced-motion behavior, export-coordinate accuracy, broader damaged/encrypted/provider-failure corpus, long-operation safe cancellation, all conversion formats. |

Evidence notation: **R** = observed runtime/hierarchy; **C** = confirmed implementation; **C-condition** = a confirmed branch whose trigger frequency or exported result still needs reproduction; **O** = observation, not a declared defect. Severity assesses the impact when the stated condition occurs, not its unknown frequency.

Source references below use exact repository-relative paths and line numbers at the audited commit:

| Shorthand | Source |
|---|---|
| M | [MainActivity.kt](../../app-host/src/main/java/com/pdfchemy/app/MainActivity.kt) |
| VM | [ui/MainViewModel.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt) |
| UI/X | `app-host/src/main/java/com/pdfchemy/app/ui/X.kt` |
| L/X | `app-host/src/main/java/com/pdfchemy/app/logic/X.kt` |
| J/X | `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/X.kt` |
| IPC/X | `pdf-ipc/src/main/java/com/pdfchemy/app/X.kt` |
| Billing/X | `app-host/src/main/java/com/pdfchemy/app/billing/X.kt` |
| Strings | `app-host/src/main/res/values/strings.xml` |

### Validated runtime evidence index

| Evidence | What it establishes |
|---|---|
| [02 dashboard](evidence/02-dashboard.png), [13 dark dashboard](evidence/13-dashboard-dark.png) | Existing visual language and category-first access; no tool-search or primary Open PDF control |
| [04 compression entry](evidence/04-compress-initial.png) | Clear file-first empty state |
| [06 hardware safeguard](evidence/06-current.png) | Additional modal: 120 pages, estimated 33MB versus available 182MB, Split First / Proceed Anyway |
| [07 defaults](evidence/07-compress-defaults.png), [08 lower options](evidence/08-compress-lower.png) | Default compression options and run action below the technical controls, after a full vertical swipe |
| [09 save picker](evidence/09-save-picker.png), [11 result](evidence/11-back-during-operation.png) | Editable name/destination and successful output; result only Share/OK. Capture 11's filename is an exploratory action label, not proof that Back canceled processing. |
| [16 document picker](evidence/16-document-picker.png), [19 Reader XML](evidence/19-reader-settled.xml), [20 next-page XML](evidence/20-reader-next.xml) | Actual SAF selection; persistent Reader control set and page navigation/rendering state |
| [26 text added XML](evidence/26-added-text.xml), [27 departure](evidence/27-leave-edited-document.xml), [28 fresh editor](evidence/28-fresh-editor.xml) | Add text, Back, reopen Editor: no save/discard decision; empty file selection on return |
| [35 Merge workspace](evidence/35-merge-workspace.png), [36 reordered](evidence/36-merge-reordered.png) | Visible input names, removal/addition/order controls; arrow reordering changed file order |
| [37 Merge save](evidence/37-merge-save.png), [38 Merge processing XML](evidence/38-merge-processing.xml), [55 Downloads](evidence/55-organizer-picker.xml) | Editable merge name; Merge displays “Compressing PDF...” while processing; merged output exists in Downloads |
| [41 large text](evidence/41-dashboard-font-200.png), [42 Settings large text](evidence/42-settings-font-200.png), [43 system Back](evidence/43-settings-system-back.xml) | Dashboard description truncation; Settings remains usable; system Back from Settings exits to launcher |
| [45 small phone / large text](evidence/45-small-phone-font-200.png), [48 landscape](evidence/48-phone-landscape.png), [49 large display](evidence/49-tablet-dashboard.png) | Category-title truncation at 320dp/font 2.0; landscape scrolling; bounded large-display layout |
| [54 Organizer entry](evidence/54-organizer-empty.xml), [57 workspace](evidence/57-organizer-ready.xml), [60 delete](evidence/60-organizer-deleted.xml), [61 save](evidence/61-organizer-save.xml), [63 result](evidence/63-organizer-result.xml) | Promised drag; actual action set lacks Undo/Redo; deletion changes seven pages to six; copy save completes with Share/OK |
| [68 damaged-document error](evidence/68-damaged-error.xml), [69 recovery](evidence/69-damaged-recovery.xml), [fixture](fixtures/intentionally-damaged.pdf) | Script-free truncated PDF is described as containing embedded scripts; OK returns to source selection without a cause-specific explanation |
| [20 Reader frame data](evidence/20-reader-next-gfxinfo.txt) | Emulator frame sample after a reset; not a real-device or isolated render-time measurement |

Exploratory captures with unsuccessful taps, stale dumps, still-portrait rotation attempts, or ambiguous Back timing are not used as proof. In particular, captures 23–25 do not establish the annotation-loss finding; 26–28 do. Captures 46–47 are not landscape evidence; 48 is.

## Product Philosophy Assessment

| Intended quality | Assessment |
|---|---|
| Premium | Visual baseline is convincing; inconsistent result handling, misleading failures, and unsaved-session loss undermine trust more than styling does. |
| Slick | Short screen transitions and consistent cards work. Permanent Reader editing chrome and generic processing feedback interrupt the intended simplicity. |
| Straight to the point | Common visible tools take two meaningful navigation taps from Home. Search is absent, and deeper tools require scrolling/knowing categories. |
| User-controlled | Explicit document selection and copy-save destination are strengths. Forced first-run agreement, absent undo/redo, and missing safe cancellation/default controls are gaps. |
| Powerful without effort | Breadth is real. Discovery, range-based page operations, and inline technical settings transfer too much complexity to the user. |
| Distraction-free reading | Not met by the full-layout PDF surface: persistent editing controls, basic navigation, missing reading gestures, and a screen-unrestricted free-user banner. |

The problem is not that the application has too many tools. It is that the interface does not yet conceal enough of the coordination required to use them.

## What Is Already Good

- Preserve the four-card dashboard, its visual hierarchy, restrained category descriptions, and tool-centered purpose. No telemetry, usage scores, or behavior-based ordering were found in the inspected navigation.
- Preserve the current rounded surfaces, cyan/purple accents, category icons, and light/dark direction. The screenshots do not justify replacing the design system.
- Preserve the category/card model. It needs search and selective clarification, not a giant flattened grid.
- Preserve the existing Premium entry point. It is available without dominating tool access.
- Preserve source-first empty states for compression, editor, Merge, and Organizer. Their relevant selection actions are immediately understandable.
- Preserve Merge's visual workspace: selected files, explicit order, easy removal/addition, arrows, and implemented drag support. Runtime arrow reordering worked.
- Preserve Organizer's adaptive thumbnails, selected border, position badges, rotation preview, duplication, blank insertion, and protection against deleting the last page. Build reliable state/undo around this foundation.
- Preserve Android SAF and explicit export of copies. Do not bypass platform storage/security for cosmetic picker continuity.
- Preserve the concise, grouped, scrollable Settings structure. At font scale 2.0 on the normal phone it remained usable.
- Preserve opt-in local history, bounded retention, clear history, and clearing on disable. These are useful privacy decisions, not a basis for behavioral promotion.
- Preserve the skippable four-slide tour and Settings replay. The separate consent blocker is not a reason to redesign the tour.
- Preserve purposeful 220–260ms navigation transitions. No evidence supports slower flourish or wholesale motion replacement.
- Preserve masked password entry and confirmation in Protect, honest format-specific conversion explanations, isolated document processing, and secure document surfaces.

Evidence: M:746–767, 1557–1648, 1860, 2016, 3286, 4140; UI/OrganizeScreens:309–489; UI/PageOrganizerScreen:287–459; UI/SecurityScreens:203–255; L/HistoryRepository:24–26, 49–51, 69–86; M:4201–4269; UI/SecureScreen:16.

## Navigation & Information Architecture

The dashboard is a useful front door. Its category cards should stay. The navigation currently contains 3 Compress entries, 16 Create entries, 24 Organize entries, and 14 Check entries. This is enough breadth that category navigation alone cannot satisfy the directive. There is no tool-search field, control, implementation, or destination in the inspected screen registry (UX-01).

For a visible tool, Home → category → tool is two meaningful taps. Selection adds another action before Android's picker interactions. This is reasonable. For Sign, Organizer, Crop, or deeper inspection functions, the cost also includes category knowledge and scrolling. At the tested phone width, Organize is one column and initially shows approximately five tool entries. Sign is ninth; Organizer eighth. Search should eliminate browsing for a known intention, using deterministic tool-name and conceptual aliases such as “join”, “combine”, “make smaller”, and “remove pages”. No usage history is required.

Reading is described under “Edit & Annotate PDF” and a seventh-position “Reflow Reader & Bookmarks” entry. The dashboard has no direct Open PDF reading action. This makes the primary reading experience look subordinate to editing (UX-02).

Some conceptual placement deserves a narrow review: Grayscale and Fast Web View live in Organize rather than alongside optimization; Extract Text is in Check while Extract Images is in Organize; Quick Fill & Sign appears in both Create and Organize. “Scan” and “Scan Document” launch different implementations with overlapping intent. These are discoverability issues, not proof that all categories must change (UX-03).

## File Selection & Document State

The empty tool screens generally put selection first and show chosen names afterward. Android picker transitions are familiar and security-correct. Merge's selected files remain visible; adding files does not force a complete restart. The separate Recent choice can reduce picker invocations when entries are valid.

The main continuity defect is Reader → another tool. The editor receives the initial external PDF URI, but has no current-document tool launcher. Leaving returns to Home/Organize, and tool destinations are constructed without that URI. Opening another tool therefore requires selecting the same document again (UX-18). Existing in-editor annotation actions already use the current PDF; they are not part of this reselection defect.

The hardware safeguard's Split First branch also navigates to a blank Split screen rather than carrying the already selected input (UX-04). The runtime modal appeared for a 120-page, approximately 2.5MB document with an estimated requirement of 33MB and 182MB available. Code permits a page-count-only caution above 50 pages. The safeguard itself is legitimate; its escalation threshold and wording need measured calibration, not removal (O-01).

Document identity should survive staging. Current filename suggestions can derive from a randomized snapshot URI instead of the displayed source name, and reflow resume keys can similarly change across reopenings. Storage architecture may remain unchanged while identity is kept stable (UX-07, UX-25).

## Tool Workflows

### End-to-end journey traces

Tap counts exclude text entry, Android provider navigation, and scrolling unless explicitly stated. They are observed/source-derived counts, not timed usability-study results.

| Workflow | Current journey and evidence | Outcome / friction |
|---|---|---|
| A: Compress | Home → Compress category → Compress PDF → Select PDF → hardware modal for the harness asset → Proceed Anyway → review controls → full vertical swipe to Optimize & Save → Android name/destination → Save → processing → Share/OK. R:04,06–11; C:M:2177–2987. | Output created; no automatic opening, which is good. Open result is not offered: desired last step cannot be completed directly from completion. Selection used the debug asset, not normal SAF. |
| B: search/Merge | Search step cannot start because no tool search exists. Alternative: Home → Organize → Merge → Select PDFs → actual multi-select picker → two files → verification → workspace → one arrow tap reorders → Merge → CreateDocument → Save. R:29,31–38,55; C:UI/OrganizeScreens:309–489. | Output exists. Ordering/reselection handling is good. Processing falsely says Compressing. Share action exists in shared result code, but result-share chooser was not exercised; no claim of a runtime-complete B. |
| C: external PDF/Reader | Source trace: external VIEW PDF → initial URI → editor. Runtime exercised the same editor through SAF selection → read page 1 → Next → add text → Back → reopen empty editor. R:16,19,20,26–28; C:M:571–598,875–879. | PDF search is unavailable; current-PDF tool handoff and preserved Reader return are unavailable. A direct ADB external VIEW failed on shell URI permission, so the external entry itself is source-verified, not runtime-proven. That shell limitation is not an app defect. |
| D: Organizer | Home → Organize → scroll → Organizer → Select → SAF → seven-page workspace → select page → Move Backward → Delete → six pages → Save copy picker → Save → success. R:53–64. | Undo and Redo steps cannot be performed: neither control/state exists. Reset loses all session changes, not just the latest one. Output created and success shown; exported page content was not verified. |
| E: failure/recovery | Runtime: Editor → SAF → intentionally truncated script-free PDF → verification → false embedded-scripts threat → OK → Select PDF. R:65–69. Source-traced operation failures include raw details and Split invalid ranges returning without output yet publishing Success. C:UI/VanguardPicker:201–220; VM:360–377,727–730; M:1040–1120. | Actual input failure is misdiagnosed; only return-to-selection recovery. Full operation/password/provider-failure corpus was not executed; other branch certainty is separate from frequency. |
| F: long operation/cancel | Runtime compression and Merge → indeterminate shared overlay. Source: no user Cancel; no shared operation/page/phase payload; OCR callback accepted but never emitted. R:10,38; C:M:939–980; VM:167–173; L/PdfOcrEngine:11–29. | Cancel-to-safe-return journey is unavailable. Safe interruption/commit boundaries need backend-specific tests; elapsed backend processing is not treated as a UI freeze. |

### Major tool assessment

| Tool / family | Assessment |
|---|---|
| Merge | Strong workspace, usable order/removal/addition, drag and arrows. No evidence supports replacing it. Names truncate in compact rows; demonstrate same-prefix ambiguity before changing row structure. Shared progress/results need improvement. |
| Split / Extract Pages | Filename selected first, but page ranges require numerical mapping rather than visual selection. Invalid/out-of-range tokens can yield a no-output Success. Folder output needs a useful multi-output result, not sharing a directory URI. |
| Delete / Rotate | Input-first is good. Typed ranges lack content preview. Rotate navigates away immediately after starting work, weakening failure recovery/context. Existing Organizer can provide a reusable visual route without removing efficient range entry. |
| Organizer | Useful visual foundation; no incremental undo/redo or actual page drag. Thumbnail failures must not decide which source pages exist in an export. |
| Compression / Batch | Clear file summary, useful analysis and profiles, off-thread processing boundary. Technical options are inline above Run; late analysis can overwrite choices; profiles are not persistent user defaults. Batch output naming/collisions depend on provider behavior. |
| OCR | Source selection and searchable-PDF creation are straightforward. The screen defines page progress, but the engine does not call its callback. No user cancellation control. Scanned-PDF reflow's OCR recovery is unreachable for its ordinary populated-empty-sections result. |
| Protect / Unlock | Password masking and matching confirmation are good. Some guarded picker flows do not expose the available unlock recovery callback. Wrong-password and inaccessible-source recovery require a runtime corpus. |
| Signing / Quick Fill | Dedicated preview and signature workflow exist. PKI signing failure can be silent; failed page rendering can leave a prior page image under a new page index. Signing has Clear rather than undo. Quick Fill/editor undo also needs chronological action handling. |
| Redaction | Broad manual/pattern functionality, but manual targets are represented by page/percentage coordinates and Smart PII has no match review before export. Failed search can retain earlier matches. Mixed editor redaction/export has a separate correctness defect. |
| Repair | Dedicated diagnostic approach is useful. Selecting a new document does not immediately clear the old diagnosis; failure may leave misleading old assessment. |
| PDF→images / Extract images/text | Useful extraction coverage. Completion handling varies: Extract Images returns with a toast, several outputs do not publish a shareable URI, and multi-file/directory results are not modeled consistently. |
| EPUB / CBZ / TXT→PDF | Format-specific guidance exists. EPUB/CBZ still use a PDF-auditing picker while Vanguard is on by default; valid archives encounter PDF parse rejection. TXT is not assumed to follow the same failure branch. |
| Images→PDF / Office / text conversions | Input previews and named format limitations are good. Images list uses URI keys while repeated selection can append the same URI, a conditional duplicate-key failure risk. Export formats must remain honest, not marketed as equivalent fidelity. |
| Scan / crop / forms / compare / attachments / tables / bookmarks / find-replace / image replacement | Source/state/preview paths inspected. No blanket claim of runtime correctness. Scan filtering already uses a background dispatcher; preserve it. Reader annotation gaps are not disproved by separate standalone tools. |
| Watermark / page numbers / headers-footers / layouts / N-up / booklet / deskew / grayscale / flatten / linearize | Configuration, navigation, save/result patterns inspected. Standardize only demonstrated shared defects: document handoff, progress, naming, result actions, and defaults; no invented tool-specific complaints. |
| Inspection / metadata / sanitation / PDF-A / fonts / text cleanup | Source surfaces and error/result contracts inspected. Security protections should stay. Technical implementation names should not leak through failure messages. |

## Advanced Controls

Compression does not currently separate a simple default run from an advanced screen. Its target-size/profile controls, grayscale, lossless optimization, and metadata choices sit in the same scrolling flow before the run action. In the observed phone journey, one full vertical swipe was required to reach Optimize & Save after reviewing the selected document (UX-34).

The direction is progressive disclosure within the existing visual system: source, named default profile, relevant summary, Run; a clearly discoverable Advanced destination for the larger configuration set. Side-slide hierarchy fits the stated preference. Preserve options and changes on return. Do not replace the current tool with a new aesthetic or bury all meaningful choices.

Document analysis is deterministic and useful; it is not prohibited behavioral personalization. However, analysis resets/applies settings asynchronously, which can overwrite an explicit user choice, and tool setters do not establish a resettable recurring profile preference (UX-35). User choice should take precedence over later automatic analysis. Reflow's font/theme reset and output/reading defaults are separate small preference gaps, not grounds for a settings jungle.

## Page Organization UX

Organizer supports content thumbnails, explicit selection, order badges, per-page rotation, duplication, blank insertion, delete, and copy export. Keep these decisions.

The runtime seven-page session exposes Reverse, Rotate All, Reset, six page actions, and Save. Deleting changes seven to six pages immediately, without Undo or Redo. Reset restores the whole starting state. It is not a substitute for session undo (UX-11).

The empty-state promise says “Drag”, but page cards only select on click and movement is one position per arrow press. Moving page 20 to the beginning requires selecting it and pressing Move Backward 19 times. Merge already implements drag; page cards do not (UX-13).

Thumbnail generation is serial, publishes the grid after the loop, and lacks useful per-page loading/failure feedback. More importantly, a page model is only added if its bitmap is non-null. Save exports only page models. A failed preview can therefore become an unrequested deletion in the output (UX-12). Page identity must be independent of preview success. This conditional export branch is source-confirmed; failure frequency and a produced example are not established.

Range-only Split/Delete/Rotate screens create unnecessary mental mapping. Offer visual selection where practical while retaining typed ranges as an efficient optional control (UX-10). No new dashboard/navigation model is needed.

## Reader Mode

### Reading experience and controls

The full-layout PDF surface is `PdfEditorScreen`, including external PDF opening. In View mode it shows a fitted bitmap; the VIEW interaction branch is empty. Previous/Next and a page counter are the navigation system. It does not implement pinch zoom, double-tap zoom, pan, continuous PDF scrolling, swipe-to-page, page jump, PDF text search, Reader thumbnails, or personal bookmarks (UX-15).

This is a capability/interaction gap, not a preference for Adobe's appearance. A dense page cannot become legible just because the app can technically show it.

The top editing controls and bottom View/Draw/Highlight/Text/Stamp/Redact/color/navigation controls persist after settling. No tap-to-reveal/hide command-centre state exists. Reflow similarly retains its top and font/theme bars (UX-16). The document should own the idle reading surface; existing controls can move behind a revealed command surface and secondary annotation state without changing their visual language.

### Gestures, navigation, and wide modes

Single-page display, automatic two-page selection for landscape/large widths, and a tabletop posture branch are present. That architecture is useful. It currently has a correctness gap: wide/tabletop branches render plain Images without the single-page annotation input, overlays, or rotation treatment while controls remain available. Existing annotations disappear from that preview; they are not deleted from state (UX-20). Validate actual large/foldable devices before changing the mode policy.

Most icon tools have content descriptions and several have text labels. Keep those labels accessible, but do not make all labels permanently occupy Reader. Add a brief/long-press explanation for ambiguous tools, using the existing terminology. Color and theme choices themselves lack meaningful choice/selected semantics (UX-49).

### Search, thumbnails, bookmarks, and sharing

The separate reflow reader has extracted-text scrolling, section search/highlighting, a table-of-contents drawer, typography/theme controls, and TTS. These are strengths. They are not PDF-layout search or personal reading bookmarks. Its search counts/navigates matching sections rather than each occurrence, jumps to section start, and its outline UI renders only top-level items despite parsed children (UX-27).

PDF-layout Reader has no search, thumbnail navigator, bookmark management, share, or print surface. Standalone bookmark editing and global output sharing are not equivalent to reading/navigation capabilities. Reflow should stay as an alternative text-oriented mode, not be used to dismiss these gaps.

### Annotations, state, and export trust

Draw, highlight, text, stamp, and redact exist. Separate signing exists. Underline, general shapes, and a signature action are absent from the Reader tool set; these are lower-priority capability gaps after basic reading and reliability.

Runtime Text → Add → Back leaves without a save/discard decision; reopening Editor shows Select PDF again (UX-17). The evidence confirms session loss; protected screenshots prevented visual inspection of the annotation itself. Source confirms the Add handler writes in-memory annotation state and Back disposes it.

Undo is not chronological. It removes drawing actions first, then text, then stamps. A pen stroke followed by text therefore undoes the older stroke first. Rotation and redaction are excluded, and Redo is absent (UX-19).

Two conditional export defects require focused reproduction: text/stamp preview uses inconsistent pixel/dp/normalized coordinates (UX-22); a redacted page is exported from its original raster and the branch skips other new drawings/text/stamps/rotation (UX-21). Originals remain protected. The risk is silently wrong saved copies.

### Performance and reading state

Single-page pixel-wire decoding/allocation happens on the UI caller context after isolated rendering returns. This is confirmed main-thread image work (UX-23), not proof that all backend rendering occurs on the main thread. The two-page helper has an IO wrapper. Each fresh render also replaces document content with a spinner; retain the last valid page/preview while loading where safe.

PDF editor does not persist the page. Reflow stores position using a URI hash, but staging external originals creates randomized snapshot names, so a later reopen can get a different key. Reusing a staged URI can still restore; persistence is not universally broken. Reflow writes position without a user opt-out. Entering/recreating Reflow initializes Light/16/Sans rather than persisted defaults; selecting another file while staying in the same screen can retain the current choices (UX-25/26). A stable local document identity and a clear remember-position preference are sufficient; no behavioral analytics are needed.

Scanned reflow creates empty sections for each page while its OCR action requires an entirely empty section list. The normal scanned-document branch therefore misses its recovery action (UX-24).

## Saving & Output

Single-file tools generally use Android CreateDocument: destination and suggested filename can be edited before processing. Explicit copy export, not automatically opening the result, is correct and should remain.

The completion contract is insufficient. The shared Success dialog contains only Share/OK. It lacks Open, Show in Files, Another Operation, and Home; some tools do not provide an output URI at all, and Split uses a directory URI as though it were a single shareable file (UX-06). Runtime compression and Organizer confirm the two-action dialog. Build a consistent result contract around actual file(s), not one generic message.

Filename suggestions use `lastPathSegment` in FileUtil, which can be an opaque SAF identifier or `snapshot_<random>` rather than the retained display name. Other tools use generic timestamps. The observed compression name was good because the screenshot harness bypasses ordinary staging; it does not disprove the normal-flow naming issue (UX-07).

No app-controlled preferred save destination setting/persistence was found. Single saves repeatedly invoke CreateDocument; batch compression and Split invoke tree selection. Android may remember its own provider/location; this is not an explicit PDFchemy preference with change/reset controls (UX-08).

Collision handling must be provider-aware. Android's CreateDocument cannot overwrite an existing document and normally requests a renamed copy; this is a safe platform behavior, not a destructive overwrite bug. PDFchemy nevertheless lacks the directive's explicit overwrite-versus-copy choice, and direct batch `createFile` behavior depends on the provider. Offer a deliberate, original-safe output collision decision without promising unsupported universal behavior (UX-09). [Android Storage Access Framework documentation](https://developer.android.com/training/data-storage/shared/documents-files).

## Progress & Cancellation

Merge displayed “Compressing PDF...” during its run. The shared overlay has a spinner and generic compression label regardless of operation. Its state has no task/page/phase data and no Cancel control (UX-05).

OCR has a progress-shaped UI but its current engine accepts `onProgress` without invoking it. It cannot currently supply the desired “page 18 of 62” feedback. Do not mistake a dormant progress API for implemented progress.

Direction: report operation/phase honestly, add page counts only when real, keep progress visually responsive, and implement Cancel only at documented safe boundaries. Distinguish cancellation from Back/dismissal and confirm output cleanup/original safety. The gateway already contains abort/cleanup and validation behavior, but this is not sufficient to declare every operation safely cancelable.

The overlay uses a plain Box without processing-specific input consumption/Back handling. Whether users can navigate or trigger underlying controls while processing needs reproduction; exploratory Back timing was ambiguous, so touch-through is an observation, not a proven defect (O-02).

## Errors & Recovery

Guard failure is over-collapsed. Parse failure, inaccessible provider input, and some page-render failures can produce the same embedded-scripts/security-threat message. Runtime selected an intentionally truncated, script-free 96-byte PDF and received “This PDF contains embedded scripts or automated execution hooks.” OK returned to the empty source-selection screen. A genuine script block should stay blocked, but the message is false for this damaged fixture and offers the wrong recovery (UX-29). [Captured error](evidence/68-damaged-error.xml).

Compression translates several expected cases. Its fallback still carries raw exception/class/cause detail; other task errors forward library messages. The Error Details dialog displays those details directly (UX-30). Human-readable primary copy plus meaningful recovery should be the rule; “Details” is not permission to show implementation terminology.

Invalid Split ranges can be filtered to an empty set, return without outputs, and then produce Success (UX-32). Repair can show an earlier file's diagnosis after selection/failure on a new file (UX-39). PKI signing failure/page rendering and failed redaction search also lack reliable state-specific feedback (UX-37/38).

Do not add repeated original-safe nag dialogs. Copy-save already protects originals. The audited routes should explain protection at relevant commit boundaries, not on every action. Full runtime failure injection, password recovery, missing source, revoked SAF permission, and output-write failure remain required validation work.

## History

History is optional, local, bounded to 20 entries, and clears on disable. Preserve that privacy model. Recent files need not dominate Home.

The current Home displays only the latest five when present; there is no dedicated History destination, empty-history action, or per-entry remove. Older retained items are not fully reachable there (UX-40).

Stored output labels such as “Edited PDF” or “Protected PDF” can omit extensions, while Home decides Reader routing from the name extension. Merge's Recent selection does not filter to available PDF inputs and can include other output types/folders. These assumptions make Recent unreliable (UX-41). Store file identity/type independently from display labels, filter per operation, and distinguish missing/revoked sources from unsafe content.

Persistent permission after reboot/provider changes was not validated. Do not claim that all Recent sources remain available or that all permissions are lost.

## Settings

Keep the existing App Preferences and About/Legal grouping. Theme/language/haptics/sound/history/Vanguard/default viewer controls are a reasonable compact structure. Font scale 2.0 did not demonstrate broken Settings controls.

Android system Back from Settings exits the Activity rather than returning Home; the toolbar's Back goes Home. Runtime confirmed this inconsistency (UX-42). The same screen-state architecture creates the corresponding Premium risk; Premium system Back was source-traced, not separately exercised.

Category/tool cards call platform haptic feedback regardless of the app's haptics toggle (UX-43). Preserve the feedback style and enforce the preference; no new haptic system is justified. Actual vibration strength/frequency was not measurable on this emulator.

The Home theme control looks like a light/dark toggle but cycles System → Light → Dark → System. If System is already light, the first tap can appear to do nothing (UX-44). Make state/action predictable without changing the theme palette.

Add only demonstrated recurring preferences: remember reading position, Reader defaults, a named compression profile where useful, and preferred output location. Ensure change/reset behavior. Do not create a setting for every advanced switch.

## Onboarding

The existing tour has four slides, immediate Skip, dismissal with Back, and a Settings replay. Preserve its length and basic mechanism. The source-level complete first-run path is agreement, three Next actions, Get Started; skipping is agreement plus Skip, with UMP interactions conditional.

The separate initial agreement is non-dismissible and only offers “I Agree”; its text includes advertising-data use/personalization. No decline/continue-without-that-agreement path is present. This is a user-control UX finding (UX-45), not a legal compliance conclusion. UMP runs independently, so the app's custom gate and actual platform consent state are not the same flow.

First-run agreement was observed before switching to the debug harness; the tour itself was source-audited, not traversed in the harness. The fixed-height pager/icon needs a landscape/font-scale check before declaring clipping. No replacement onboarding or feature-by-feature tutorial is recommended.

## Ads & Premium

Ads were suppressed by the existing debug screenshot harness. The following inventory is based on actual call sites and eligibility, not runtime ad impressions. One global live banner exists; an additional BannerAd helper has no callers and is not counted as a placement.

| Current placement / timing | Assessment |
|---|---|
| Global banner on Dashboard | Eligible for free + agreed + non-harness sessions. A menu context can be acceptable. Verify stable reserved geometry, calm spacing, and no displaced task action before calling it successful. |
| Same banner on category menus | Potentially natural browsing context. Must not create card movement or obscure navigation. No load-time shift was demonstrated. |
| Same banner on tool configuration | Potentially acceptable only outside meaningful controls and without distracting from selected input/run/save. Shared placement does not account for screen-specific density. |
| Same banner on editing / page organization | Needs harsh runtime evaluation: less acceptable during active precise manipulation than at a post-operation break. It reduces content space but no gesture theft was proven. |
| Same banner during processing | Persistent parent placement remains eligible. No interstitial show site was found during progress. Verify that feedback/cancellation never competes with ad layout. |
| Same banner on PDF Editor / Reflow Reader | P1: screen eligibility does not exclude Readers. It sits outside document pixels but reduces the reading viewport. This violates the clean-Reader expectation even without claiming overlay, gesture stealing, or load-time jumping (UX-46). |
| Same banner on Settings / Premium | Global eligibility also reaches these screens for free users. Context/space needs runtime verification; an existing global rectangle is not evidence that these are desirable placements. |
| Interstitial after Success dismissal / OK | Only active show sites found. Completion is a plausible natural break. However, interception of dismissal, including outside/Back, is less explicit than a deliberate completion action; verify timing and user expectation. No independent interstitial show site was found at Reader entry, file selection, active work, or ordinary screen navigation; both requests originate Success dismissal/OK. |
| Share from Success | Launches sharing directly, bypassing the interstitial path. Preserve this low-friction behavior. |

Evidence: M:741–744, 939–1017, 1148–1176; Billing/AdManager:18, 46, 52, 65, 82–85. Interstitial cooldown is 60 seconds; premium bypasses ads; unavailable ads are skipped rather than awaited. Preloads/reloads are not impressions. No rewarded, app-open, or between-page ad placement was found.

Premium's current position should stay. Its enabled purchase button can silently do nothing when product details are unavailable, and billing failures do not have adequate visible feedback (UX-47). Show loading/unavailable/retry state rather than repeatedly prompting purchase.

The displayed `$4.99` is hardcoded and explicitly retained by an earlier product instruction in code. Treat currency/price presentation as an observation to review with the owner, not an authorized pricing change (O-03).

## Accessibility & Device Layout

Observed issues are narrow and real. At approximately 320dp width and font scale 2.0, “Compress” becomes “Comp…” and category descriptions lose identifying text. At the regular width, descriptions truncate but titles remain readable. Fixed card height/line limits cause this (UX-48). Adapt text/layout constraints locally; preserve the cards and visual identity.

Actual landscape Home remains scrollable but shows only the first category row above the fold. This is a density tradeoff, not a broken navigation finding. Large-display Home remains width-capped and centered instead of stretching cards across the entire screen. The surrounding empty space is not itself a defect.

Unlabeled color/theme Box controls do not expose useful choice or selected semantics (UX-49). This is source-confirmed; a full TalkBack traversal was not executed. Do not infer tiny effective touch targets solely from 24–32dp icon drawings: Compose can expand interaction bounds. [Android Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

Phone light/dark dashboards are coherent. Document-page white is not automatically a theme bug. Reflow resetting its own reading theme is a separate preference inconsistency. Foldable posture, annotation behavior in wide modes, dialogs at large text, edge-to-edge insets on physical devices, one-handed reach, and reduced motion require follow-up tests. No theoretical compliance-driven redesign is proposed.

## Performance & Perceived Responsiveness

The measured Reader sample, after resetting `gfxinfo` and triggering Next, contains 198 frames: 34 janky frames (17.17%), CPU frame percentiles 31ms/53ms/61ms/150ms at p50/p90/p95/p99, 27 slow-UI-thread frames, and seven slow bitmap uploads. It includes the transition/loading indicator and uses a debug build/software GPU. It is evidence of poor frame pacing in this test environment, not a real-device FPS promise, a pure render latency, or causal proof against one component. [Raw sample](evidence/20-reader-next-gfxinfo.txt).

Main-thread pixel decoding is independently established in code (UX-23). Prioritize a physical-device release-like trace around page navigation, zoom once implemented, thumbnails, wide modes, and annotation toolbar changes. Keep backend work and frame responsiveness separate. UIAutomator dump/capture duration is not operation/render duration.

Organizer builds thumbnails serially and publishes only after the loop. Its loading does not communicate page-by-page progress and its bitmap set grows with the document (UX-14). Measure memory/latency at 100–500 pages before assigning an out-of-memory claim.

Compression/manipulation engines generally dispatch expensive processing away from the UI thread; scan filtering uses a background dispatcher. Preserve those boundaries. Duplicate external security checks, no adjacent-page cache, continual dashboard mesh animation, and spinner replacement are profiling candidates, not automatically proven backend/performance defects (O-04).

Reduced-motion behavior and cancellation responsiveness were not fully tested. No report claim that animation-duration scaling is ignored is justified by this evidence.

## Visual Consistency & Polish

The baseline is cohesive: rounded cards, recognizable hierarchy, bounded content width, short transitions, and consistent accent treatment. The light/dark dashboard samples support preservation. No arbitrary spacing/contrast complaint is being manufactured.

Consistency defects worth fixing are behavioral: Back destinations, haptics preference enforcement, theme-toggle predictability, progress labels, and output results. These have greater UX impact than cosmetic adjustments.

One small copy defect was reproduced in multiple security-verification hierarchies: “100%% Offline • Zero-Trust” renders a literal doubled percent sign (UX-50). Fixing that does not justify rewriting the product's language or adding personality. Keep copy professional and concise.

## Competitor Benchmark

This is a capability benchmark from official documentation, not a side-by-side performance test of installed competitors and not a visual template.

| Capability / interaction | Mature baseline | PDFchemy | Classification |
|---|---|---|---|
| Reading modes / hidden chrome | Acrobat documents continuous and single-page modes, full-screen tap behavior, and night viewing. | Fitted PDF pages and auto two-page layout exist; tap-to-hide/full PDF continuous reading does not. | Capability + interaction-quality gap, not styling preference. [Adobe viewing modes](https://www.adobe.com/devnet-docs/acrobat/android/en/mv-viewpdf.html). |
| PDF navigation / print | Acrobat Android documents reading navigation and print. | Reader provides previous/next/counter, no page jump/thumbnail navigator/print action. | Capability gap. [Adobe Android PDF workflows](https://www.adobe.com/devnet-docs/acrobat/android/en/workingwithpdf.html). |
| Personal bookmarks | Acrobat documents bookmark management. | Reflow TOC and standalone bookmark editing do not provide personal reading bookmarks in PDF Reader. | Capability gap. [Adobe bookmark management](https://helpx.adobe.com/acrobat/mobile/view-manage-files/manage-bookmarks.html). |
| Annotation tools | Acrobat documents text markup and freehand review, including highlight/underline. | Draw/highlight/text/stamp/redact exist; Reader underline/shapes/signature are not in its tool set. | Partial coverage; reliability/basic reading comes before matching every tool. [Adobe document review](https://helpx.adobe.com/sg/acrobat/mobile/review-files/review-documents.html). |
| Page-edit undo/redo | Xodo documents undo/redo for thumbnail page editing. | Organizer has Reset, not incremental undo/redo; Editor undo is type-prioritized. | Capability and correctness gap. [Xodo page-edit undo/redo](https://feedback.xodo.com/support/solutions/articles/35000202886-undo-redo). |
| Local file selection / safe copy | Android SAF gives user-controlled document/destination access and non-overwriting CreateDocument behavior. | Correct platform integration exists; app-level preferred destination and collision choice are absent. | Valid platform design with specific app gaps. [Android documents guidance](https://developer.android.com/training/data-storage/shared/documents-files). |
| Dashboard architecture | Competitors use different navigation/promotion models. | Category-card tool dashboard is intentional and coherent. | Different but valid design; no imitation recommended. |
| Performance | Requires matched device/document/build measurements. | Debug software-emulator sample only. | No comparative speed claim. |

Pinch/double-tap zoom are also explicit product requirements in this directive. Their absence is established from PDFchemy handlers; no unverified competitor gesture claim is needed. Cloud accounts, AI suggestions, promotional structures, and competitor aesthetics are outside the intended baseline.

## Full Findings Table

Types and severities are intentional: missing workflow capability is USABILITY DEFECT; incorrect state/output is BUG; known UI-thread work is PERFORMANCE DEFECT. No P0 is established. Conditional bugs are not presented as runtime-reproduced outputs.

| ID | Severity | Surface | Type | Finding | User Impact | Evidence | Recommendation |
|---|---|---|---|---|---|---|---|
| UX-01 | P1 | Dashboard/categories | USABILITY DEFECT | No tool search across 57 category entries. | Known tasks require category knowledge and browsing; Workflow B cannot start as specified. | R:02/13; C:M:1557–1648,1183–1306; category implementations. | Add deterministic name/concept alias search with direct correct destinations; preserve cards, no usage-based ranking. |
| UX-02 | P2 | Reading entry | USABILITY DEFECT | No primary Open PDF action; reading is under Edit & Annotate or seventh-position Reflow. | Reading looks like an editing subtask and is harder to discover. | R:02/14/53; C:M:1557–1648; UI/OrganizeScreens:94–97,142–145. | Expose a direct reading entry within the current dashboard hierarchy. |
| UX-03 | P2 | Categories | CONSISTENCY DEFECT | Optimization/extraction tools span unexpected categories; Quick Fill duplicated; two Scan labels overlap intent. | Browsing users must infer internal feature organization. | C:M:1864–1990,2020–2042; UI/OrganizeScreens:92–282; UI/CheckScreens:68–178. | Clarify only ambiguous naming/placement; search first; do not flatten/restructure everything. |
| UX-04 | P2 | Hardware safeguard → Split | USABILITY DEFECT | Split First opens Split without the current selected document. | Recommended recovery repeats source selection. | R:06 modal; C:M:722–724; destination construction. | Carry valid selected URI/name into the recovery tool. |
| UX-05 | P2 | Shared progress/OCR | USABILITY DEFECT | Merge says Compressing PDF; shared feedback has no task/phase or Cancel; OCR callback never emits. | Long work is opaque and cannot be deliberately stopped. | R:10/38; C:M:939–980; VM:167–173; L/PdfOcrEngine:11–29; UI/OcrScreens:194–218. | Operation-aware real progress; scoped safe cancellation with cleanup/commit rules. |
| UX-06 | P2 | Completion | USABILITY DEFECT | Shared result only Share/OK; inconsistent/missing output URIs and folder sharing. | User cannot directly open/find/reuse result; handoff differs by tool. | R:11/63; C:M:984–1017; VM:413,727–730,1623; UI/RepairPdfScreen:80; UI/RedactionScreen:110,145; UI/EbookConverterScreen:149. | Standardize file(s) result with Open, Share, Show in Files where supported, Another Operation, Home; never auto-open. |
| UX-07 | P2 | Output naming | BUG | Suggested base comes from URI last segment rather than stable visible source name; several generic timestamps. | Opaque or weak filenames require repair before save. | C:L/FileUtil:17–35; IPC/utils/DocumentStager:37,51; M:2393; UI/PdfEditorScreen:411; UI/PageOrganizerScreen:466. | Retain sanitized display name and extension; propose predictable action suffix; preserve editability. |
| UX-08 | P2 | Output destination/Settings | USABILITY DEFECT | No discoverable app preferred output-location setting/persistence. | Recurring workflows repeat destination choice; behavior depends on provider memory. | C:M:2218–2232,3299–3488; UI/OrganizeScreens:309–314,559,669; UI/OcrScreens:63. | Local changeable/resettable preferred location using valid SAF grants; picker fallback on unavailable destination. |
| UX-09 | P2 | Output collisions | USABILITY DEFECT | No explicit overwrite-versus-renamed-copy choice; provider owns duplicate handling. | User cannot predict/control the required collision policy across providers. | C:CreateDocument call sites; VM:507–508; Android SAF behavior. | Explicit provider-aware safe output decision; default copy; protect original and confirm only real overwrite. |
| UX-10 | P2 | Split/Delete/Rotate | USABILITY DEFECT | Page choice is numeric ranges without content thumbnails; Rotate returns immediately after start. | Mental page mapping and weaker error recovery context. | C:UI/OrganizeScreens:621–654,869–876,899–900,982–989. | Offer visual page selection and retain selected source through failure; keep efficient range input optional. |
| UX-11 | P1 | Organizer | USABILITY DEFECT | No Undo/Redo; Reset discards all accumulated modifications. | Accidental delete/reorder cannot be incrementally reversed before save. | R:57/60; C:UI/PageOrganizerScreen:263–269,365–459. | Session action history including page operations, preserved until commit. |
| UX-12 | P1 | Organizer export | BUG | Null thumbnail prevents page-model creation; save exports only remaining models. | Conditional unrequested page omission in output. Original remains safe. | C-condition:UI/PageOrganizerScreen:100–105,127; J/PdfPageOrganizerWorker:24–40. | Preserve every source page identity despite preview failure; retry/error placeholder; validate source/action coverage before export. |
| UX-13 | P2 | Organizer reorder | USABILITY DEFECT | Copy promises drag; cards only click and move one position per arrow. | Distant moves require repeated taps; page 20→1 needs 19 movement taps. | R:54/57; C:UI/PageOrganizerScreen:308–311,368–375,448–455; Strings:557. | Implement direct reorder or a concise Move To control; retain arrows for precision. |
| UX-14 | P2 | Organizer loading | PERFORMANCE DEFECT | Serial thumbnails, publish-after-loop, no page/failure progress; retains bitmap list. | Long documents can feel blank/unresponsive despite ongoing work. | C:UI/PageOrganizerScreen:85–115,279–287. | Incremental/lazy bounded previews and meaningful loading/error feedback; measure memory before choosing cache size. |
| UX-15 | P1 | PDF Reader | USABILITY DEFECT | View lacks zoom/pan, continuous/swipe reading, search, jump, thumbnails, bookmarks; no share/print surface. | Dense/long documents cannot be read/navigated at mature-reader quality. | R:19/20 controls; C:M:582,598; UI/PdfEditorScreen:778,1184–1195. | Complete essential reading/navigation capabilities inside the existing visual language; separate capability from competitor architecture. |
| UX-16 | P1 | Reader chrome | USABILITY DEFECT | Editing/navigation chrome is permanently visible; no reveal/hide state. | Controls compete with document content even when simply reading. | R:19 XML; C:UI/PdfEditorScreen:333–460,1105–1199; UI/ReflowReaderScreen:491–555,724–794. | Idle document-first surface; tap command centre; secondary annotation toolbar; accessible explanations. |
| UX-17 | P1 | Reader/editor departure | BUG | Back discards selected document and unsaved annotation session without a save/discard boundary. | Unexpected progress loss and reselection on return. | R:26–28; C:UI/PdfEditorScreen:73,100–101,355; M:875–879. | Preserve session where practical and guard only actual dirty departure; clear commit/cancel semantics. |
| UX-18 | P1 | Reader→tools | USABILITY DEFECT | No current-document tool launcher or preserved return session; other tools receive no URI. | User selects the same PDF again and loses reading context. | C:UI/PdfEditorScreen:65–68; M:824–836,875–879. | Pass document session/identity to technically valid tools and restore Reader state on return. |
| UX-19 | P1 | Editor/Quick Fill undo | BUG | Editor prioritizes drawings/text/stamps; Quick Fill drawings/text. Editor rotation/redaction excluded; neither has Redo. | Wrong action disappears; latest action may remain irreversible. | C:UI/PdfEditorScreen:361–382,769–770; UI/QuickFillSignScreen:338–350. | Chronological unified action history with redo and tested mixed-action sequences. |
| UX-20 | P1 | Wide/tabletop Reader | BUG | Plain-image branches lack interactive annotation/rotation overlay while controls remain. | Conditional inert controls and misleading disappearing previews; state itself is retained. | C-condition:UI/PdfEditorScreen:82–84,519–545,605–664 versus683–879. | Share the interactive page surface across modes or explicitly constrain unavailable actions; preserve wide/posture architecture. |
| UX-21 | P1 | Mixed redaction export | BUG | Redaction branch writes original raster then continues before other edit application. | New drawing/text/stamp/rotation on same page silently absent from saved copy. | C-condition:J/PdfEditorWorker:59–134,137–153. | Compose all intended edits safely in export order and validate saved output against preview. |
| UX-22 | P1 | Text/stamp coordinates | BUG | Fixed1000 normalization and pixel-as-dp preview disagree with normalized export units. | Annotation position/preview/output can diverge by size/density. | C-condition:UI/PdfEditorScreen:838–839,859–860,904–905; J/PdfEditorWorker:252–255,274. | Single page-coordinate transform for input/preview/export; physical-density and zoom tests. |
| UX-23 | P1 | Reader rendering | PERFORMANCE DEFECT | Single-page raw bitmap decode/allocation runs on UI caller context. | Image handling can stall touch/frame work during page transitions. | C:UI/PdfEditorScreen:267,284; app-host/src/main/java/com/pdfchemy/app/sandbox/NativeRendererCoordinator.kt:25–37; IPC/security/PixelWire:23–34. R:20 emulator sample is supporting, not causal proof. | Move decode off main, bound allocations/cache, profile physical release-like Reader. |
| UX-24 | P1 | Scanned reflow | BUG | Scanned result contains empty sections, but OCR recovery requires sections.isEmpty. | Normal scanned PDF shows unusable empty reading sections without existing OCR action. | C-condition:J/PdfOutlineReaderWorker:55–59; UI/ReflowReaderScreen:804,846–868. | Gate recovery on scanned/no-readable-content state, not empty-list shape; retain current document. |
| UX-25 | P2 | Reading position | BUG | Reflow resume key hashes random staged URI; PDF editor does not persist page; no reflow remember-position opt-out. | Resume is inconsistent on reopening original; preference lacks user control. | C:UI/ReflowReaderScreen:219,240,254–273,418,441; IPC/utils/DocumentStager:20–38,51; UI/PdfEditorScreen:77,237,258. | Stable local identity and explicit enable/disable behavior, preserve privacy. |
| UX-26 | P2 | Reflow defaults | CONSISTENCY DEFECT | Entering/recreating Reflow initializes Light/16/Sans; defaults are not persisted. | Repeated adjustments across sessions; mismatch with user's chosen reading/app mode. | C:UI/ReflowReaderScreen:248–250; same-screen choices can remain on file change. | Persist explicit Reader defaults with reset; document-specific overrides if useful. |
| UX-27 | P2 | Reflow search/TOC | USABILITY DEFECT | Search navigates matched sections, not occurrences; drawer drops nested outline presentation. | Repeated matches hard to locate; deeper document structure hidden. | C:UI/ReflowReaderScreen:356–387,470–485,581–586,607–609; L/PdfOutlineReader:56–60. | Occurrence-aware navigation and hierarchical TOC; distinguish bookmarks from outline. |
| UX-28 | P2 | Reader initial load | CONSISTENCY DEFECT | Initial non-rendering/pageCount0 state can show inaccessible-file UI before load returns. | A valid slow load can look like failure. | C-condition:UI/PdfEditorScreen:75–80,219–261,475–504. | Explicit loading/ready/error state; error only after a real failure. |
| UX-29 | P2 | Picker/Reader recovery | BUG | Parse/provider/render failures can be labeled embedded-script threats; some pickers omit unlock recovery. | Misdiagnosis blocks the right retry/password/file-replacement action. | R:68/69 script-free damaged fixture; C:UI/VanguardPicker:149–172,201–220,400–431; callers UI/OrganizeScreens:301,M:2208; UI/DocumentLoadGuard:6–9; UI/PdfEditorScreen:268–270; Strings:1103–1104. | Distinct damaged/encrypted/unavailable/render/security states, named failing file for multi-select. |
| UX-30 | P2 | Error details | USABILITY DEFECT | Raw library messages/exception classes/causes reach displayed details. | Technical failure explanation without useful next step. | C:VM:360–377,639,810,1395,1487; M:1040–1120. | Humanize errors centrally; meaningful details only; preserve developer diagnostics outside user copy. |
| UX-31 | P1 | EPUB/CBZ conversion | BUG | Archive picker uses PDF auditing while Vanguard defaults enabled. | Valid non-PDF archives are rejected by PDF parse checks under defaults. | C-condition:UI/EbookConverterScreen:60,267–272; UI/VanguardPicker:201,211; J/PdfSanitizerEngineWorker:14,22–33; VM:97. | Format-appropriate guarded validation; reproduce valid EPUB/CBZ; do not remove security globally. |
| UX-32 | P2 | Split invalid range | BUG | Empty filtered page set returns without output, then reports Success. | User believes a file was produced when none was. | C-condition:L/PdfManipulator:44–47,335–368; VM:727–730. | Validate range and output count; clear inline correction instead of false success. |
| UX-33 | P2 | Images→PDF selection | BUG | Repeated URI append plus URI-only Lazy list keys allows duplicate keys. | Conditional composition failure after choosing same image again. | C-condition:UI/CreateScreens:51–52,172. | Stable unique item identity or explicit deduplication; reproduce before escalating severity. |
| UX-34 | P2 | Advanced controls | USABILITY DEFECT | Compression/redaction expose substantial technical configuration in main flow. | Default path requires scrolling/technical parsing to find Run. | R:07/08; C:M:2787–2987; UI/RedactionScreen:266–362. | Separate simple defaults from discoverable Advanced screen; preserve choices on Back. |
| UX-35 | P2 | Compression choices/defaults | BUG | Late file analysis resets/applies settings after explicit choices; no recurring profile persistence. | User choice may be overwritten; repeated configuration. | C-condition:VM:208–233,580–583 overwrite timing; C:VM:184–205 in-memory setters without persistence. | Analysis may set untouched defaults only; persist deliberate named profile/reset separately. |
| UX-36 | P2 | Redaction review | USABILITY DEFECT | Manual coordinates/patterns proceed to output without visual match review. | Hard to verify which content will be permanently removed from copy. | C:UI/RedactionScreen:358–362,396–418. | Visual target review and clear commit boundary, retaining advanced patterns. |
| UX-37 | P2 | Redaction search failure | BUG | Failure branch leaves prior matches with no replacement/error handling. | Old targets can be mistaken for results of the new query. | C-condition:UI/RedactionScreen:77–79. | Preserve query/result association; explicit failure/stale state and safe retry. |
| UX-38 | P2 | Signing | BUG | PKI signing failure can be silent; failed page render can leave old bitmap with new index; Clear only. | Misleading page placement and weak recovery from mistakes. | C-condition:UI/SignPdfScreen:118–123,145–154,435–453,806–810. | Correct loading/error state, no stale page image, session undo where practical. |
| UX-39 | P2 | Repair | BUG | New input/failure does not immediately clear prior diagnostic report. | User can act on a diagnosis belonging to another file. | C-condition:UI/RepairPdfScreen:43–62,180. | Bind diagnosis to current identity; clear/show loading/error on input change. |
| UX-40 | P2 | History | USABILITY DEFECT | Only latest five Home entries; no dedicated full-history/per-item remove/empty action. | Retained older documents are hard to reach or selectively remove. | C:M:3990–4093,4003; L/HistoryRepository:69–86; screen registry. | Dedicated optional History entry with simple empty action and removal; keep dashboard tool-first. |
| UX-41 | P2 | Recent type/availability | BUG | Routing uses filename extension despite generic labels; Merge Recent includes non-PDF/folder outputs. | Wrong destination or invalid selection; missing sources misdiagnosed. | C:VM:728,757,786,854,1119,1151,1190,1232,1282,1614; M:4008–4054; UI/OrganizeScreens:329–341,352. | Store MIME/document identity separately; filter per task; availability-specific recovery. |
| UX-42 | P2 | Settings/Premium Back | CONSISTENCY DEFECT | System Back exits Activity; toolbar Back returns Home. | Accidental app exit and inconsistent navigation expectation. | R:43 Settings; C:M:548,3169–3699,4123–4186; toolbar:504–533. | Match system and toolbar Back for these destinations; test gesture navigation. |
| UX-43 | P2 | Haptics preference | BUG | Category/tool cards directly trigger feedback regardless of app toggle. | Disabling haptics does not consistently disable them. | C:M:1675–1678,2093–2096 versus VM:65–74. | Route meaningful feedback through existing preference-aware mechanism. |
| UX-44 | P2 | Home theme control | CONSISTENCY DEFECT | Binary-looking toggle cycles three states and can produce no visible change. | Tap seems ignored; System state not obvious. | C:M:782–786,1576–1580. | Expose current state/next action predictably, preserve theme options. |
| UX-45 | P1 | First-run agreement | USABILITY DEFECT | Non-dismissible advertising-data agreement only offers I Agree. | First useful action blocked without a visible decline/use-app path. | R:first-run observation; C:M:452–469; Strings:339–341; UMP:392–435. | Clear user choice consistent with real consent state; no legal conclusion or onboarding replacement. |
| UX-46 | P1 | Reader advertising | USABILITY DEFECT | Free/consented global banner has no Reader exclusion. | Reader viewport reduced and task purity compromised. | C:M:741–744,1148–1176. Ads not runtime-loaded. | Keep PDF and reflow reading clean; restrict ads to justified contexts, verify stable geometry. |
| UX-47 | P2 | Premium purchase | BUG | CTA enabled before details are available; launch can silently return; failures lack visible recovery. | Upgrade action appears broken/unresponsive. | C:M:4173–4180; Billing/BillingManager:130–137,169–181. | Loading/unavailable/retry and visible outcome; preserve current placement. |
| UX-48 | P2 | Small phone/large font | ACCESSIBILITY DEFECT | Fixed category cards truncate identifying titles at320dp/font2.0. | Users lose task labels, not just decorative copy. | R:45; contrast R:41/42; C:M:1633–1645,1727–1742; tool-card constraints2100,2152–2166. | Local flexible height/line sizing at enlarged fonts; keep visual cards and normal-size density. |
| UX-49 | P2 | Reader color/theme choices | ACCESSIBILITY DEFECT | Box swatches lack meaningful labels/selected semantics; small visual controls. | Screen-reader users cannot reliably choose/identify state. | C:UI/PdfEditorScreen:1166–1172; UI/ReflowReaderScreen:744–764; full TalkBack pending. | Named selectable semantics and verified effective touch bounds; no label clutter on PDF. |
| UX-50 | P3 | Security verification copy | POLISH ISSUE | Literal “100%% Offline” displayed. | Small professional-polish defect. | R:17/34/56 XML; Strings security-verification copy. | Correct percent escaping; retain concise tone. |
| O-01 | Observation | Hardware safeguard | SUBJECTIVE ALTERNATIVE | Page-count-only caution triggered with estimated33MB/available182MB. | Extra modal may be disproportionate, but estimate accuracy not established. | R:06; C:L/DeviceGuard:assessTask pageCount>50 branch. | Profile threshold/wording; preserve necessary memory protection and avoid unmeasured removal. |
| O-02 | Observation | Processing overlay | USABILITY DEFECT | Overlay lacks explicit input/Back interception. | Possible underlying interaction/state change; not reproduced. | C:M:939–980; exploratory Back timing inconclusive. | Reproduce touch/Back during long work; define safe navigation/cancellation contract. |
| O-03 | Observation | Premium price | SUBJECTIVE ALTERNATIVE | Hardcoded$4.99 intentionally retained rather than localized product price. | Potential currency/price mismatch; no current billing transaction verified. | C:Billing/BillingManager:27,108. | Review existing product decision, not an automatic price change. |
| O-04 | Observation | Perceived performance | PERFORMANCE DEFECT | Repeated audit/render-loading/dashboard-animation candidates need attribution. | Potential avoidable latency/recomposition; causal cost unmeasured. | C:M:579,1408–1426,1466–1484; UI/PdfEditorScreen:233,274,506–517. R:20 frame data limited. | Release-like device tracing; only optimize measured cost, retain purposeful motion. |

Observation rows use a subject/type to route investigation; their severity explicitly means they are not established defects. No subjective visual alternative is ranked as a required fix.

## Zero-Tolerance Violations

Confirmed or source-established violations of the directive:

- Hidden functionality/unnecessary browsing: absent tool search (UX-01), indirect Reader entry (UX-02), category ambiguity (UX-03).
- Forced reselection: Reader→tools and safeguard→Split (UX-18/04).
- Unexpected state/undo loss: unsaved Reader departure, nonchronological undo, missing Organizer undo/redo (UX-17/19/11).
- Unclear or wrong output: incomplete results/naming/destination/collision control and conditional export omissions (UX-06–09/12/21/22).
- Poor Reader interaction: missing essential reading controls and permanent chrome (UX-15/16); main-thread decoding is a responsiveness defect (UX-23).
- Reader advertising: global eligibility includes Reader (UX-46); overlay/gesture theft/layout jumps are not claimed.
- Technical/misleading failures: raw details and false threat diagnosis (UX-29/30), false Split Success (UX-32).
- Inconsistent controls: Settings Back, haptics toggle, theme control (UX-42–44).
- Excessive basic-flow configuration and opaque progress (UX-34/05).

Not proven: ad-induced layout shifts, touch-through during processing, destructive overwriting of originals, physical-device gesture latency, or low-memory state loss. No P0 was demonstrated. Do not turn these unverified risks into facts.

## Top 10 Highest-Value Improvements

Cost/risk are relative estimates, not engineering commitments. Grouping related findings reflects shared UX contracts, not a proposed giant refactor.

| Rank | Improvement | UX impact | Cost | Risk / validation need |
|---|---|---|---|---|
| 1 | Protect editing/output correctness: dirty session departure, chronological history, preview/export transforms, mixed redaction, source-page preservation (UX-11/12/17/19/21/22). | Very high: prevents silent loss and untrustworthy output. | Medium–high | High: mixed actions, export comparison, failed-render cases. |
| 2 | Complete essential PDF reading/navigation and document-first chrome (UX-15/16). | Very high: primary product experience. | High | Medium–high: gestures, rendering, annotation coexistence. |
| 3 | Deterministic tool search with aliases/direct destinations (UX-01). | High across nearly every tool journey. | Medium | Low–medium: catalog accuracy and conceptual-query tests. |
| 4 | Current-document Reader→tool→Reader continuity; direct reading entry (UX-02/04/18/25). | High: removes repeated picker work and context loss. | Medium | Medium: grants, snapshot identity, dirty sessions. |
| 5 | Human recovery and valid outcomes, including archive picker, scanned OCR, invalid ranges, stale previews/reports (UX-24/29–32/37–39). | High: failed tasks become understandable/recoverable. | Medium | Medium: damaged/encrypted/provider/format corpus. |
| 6 | Clean Reader ad eligibility; preserve quiet Premium/menu placement (UX-46). | High for free-user reading. | Low | Low–medium: runtime free/consented ad matrix. |
| 7 | Honest operation progress and backend-safe cancellation (UX-05). | High for long operations. | Medium–high | High around output commit/cleanup; no universal cancel assumption. |
| 8 | Consistent result/file identity/save preferences/collision decisions (UX-06–09). | High recurring workflow improvement. | Medium | Medium–high: provider behavior, multiple outputs, original safety. |
| 9 | Reader/thumbnail responsiveness with physical-device profiling (UX-14/23). | High on large documents. | Medium–high | Medium: measure first, bounded caches, memory pressure. |
| 10 | Simple-default/Advanced separation and choice persistence; then local control/accessibility corrections (UX-26/34/35/42–44/48/49). | Medium–high: less cognitive effort and more predictability. | Medium | Low–medium: font/device/default reset tests. |

The first-run choice defect (UX-45) should also be resolved in the initial user-control phase; it is not ranked as a cosmetic onboarding project. Purchase readiness (UX-47) is a contained reliability fix. Neither calls for more upgrade prompts.

## Changes NOT Recommended

- No replacement design system, palette, typography identity, dashboard concept, or competitor-themed redesign.
- No giant unstructured tool grid, wholesale category migration, or new navigation architecture without further measured evidence.
- No telemetry dashboard, storage-saved counters, productivity scoring, gamification, recent-tool ranking, AI suggestions, behavioral personalization, or analytics-driven promotions.
- No removal/repositioning of the existing Premium button solely for visual preference; no aggressive upgrade interruptions.
- No redesign of the grouped Settings structure or replacement of the four-slide skippable tour.
- No new jokes, mascots, marketing slogans, cute errors, or conversational UI personality.
- No custom picker that bypasses Android storage/security. Improve app-side continuity around SAF.
- No replacement Merge workspace. Keep visible order, drag/arrow controls, addition and removal.
- No removal of security auditing, original-safe copy export, secure capture protection, or legitimate memory safeguards because they complicate screenshots/testing.
- No permanent Reader labels/toolbars or Reader ad experiment by default. Reveal useful commands when needed.
- No automatic output opening, forced overwrite, or modal protection reminder on every action.
- No assumption that a long operation is a frozen UI, that software-emulator jank is a real-device verdict, or that empty tablet margins are broken design.
- No speculative accessibility or motion overhaul. Fix observed truncation and control semantics, then validate remaining risks.

## Suggested Implementation Phases

This sequence is a proposal only. Nothing below was implemented.

| Phase | Scope | Review gate |
|---|---|---|
| UX-1 — correctness and user control | Reproduce/fix conditional export loss, source-page preservation, stale file state, chronological undo/redo, dirty departure; first-run consent choice. | Original hashes unchanged; saved-copy inspection matches intended actions; no false Success; explicit commit/discard rules. |
| UX-2 — Reader experience | Basic zoom/pan/continuous/page navigation/search/thumbnails/bookmarks, hidden chrome/secondary annotation state, current-document handoff/return, clean ad eligibility, stable reading identity. | Workflow C on external PDFs and actual phones; landscape/wide/posture annotation parity; large/dense PDFs readable without tool clutter. |
| UX-3 — discovery and workflow consistency | Deterministic tool search, narrow category naming corrections, default/Advanced separation, output naming/result actions/location/collision policy, truthful recovery/progress/cancellation. | Workflows A/B/E/F end to end; no repeated valid input selection; provider/multi-output matrix. |
| UX-4 — performance and device validation | Off-main decode, incremental bounded thumbnails, measured cache/transition work, ad geometry, memory pressure, physical release-like traces. | Recorded frame/latency/memory before/after under matched documents/devices; no placeholder-as-page-loss. |
| UX-5 — contained polish | Back/theme/haptics consistency, billing feedback, large-text adaptation, TalkBack choice semantics, percent typo; preserve existing style. | Light/dark,320dp/large phone/tablet/landscape, font2.0, reduced motion, TalkBack and haptics settings checked. |

### Remaining validation matrix before implementation sign-off

- Real free/consented ad loads across Dashboard, menus, tool configuration, editing, Reader, Settings, Premium, progress, and completion; load/no-fill/reload geometry and interstitial dismissal timing.
- Valid EPUB/CBZ, text PDF, scanned PDF, encrypted/wrong-password, malformed PDF, intermittent provider, revoked permission, disappeared Recent source, write failure, and duplicate output names across multiple providers.
- Mixed pen/text/stamp/rotate/redact action order, undo/redo, Back, rotation, process death, export comparison, and wide-mode preview parity.
- Page-preview failure injection proving all original page identities remain represented; 100–500-page loading/memory tests.
- Complete result Open/Share/Files and multi-output sharing behavior; safe cancellation before/during/after commit. No original modification permitted by these workflows.
- Physical phone/tablet/foldable, landscape, large text, TalkBack, one-handed reach, haptic on/off, animation scaling, and local reading preference disable/reset.

Audit conclusion: preserve the visual baseline. PDFchemy's next UX gains come from making its power predictable and its document/session boundaries trustworthy, especially in Reader. Stop for review; implementation requires a separate approved phase.
