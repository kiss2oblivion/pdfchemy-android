# PDFchemy Android — Audit Evidence Guide

This guide packages the existing audit evidence for review. It adds no new observations or tests. Findings, severity, source references, and recommendations remain in the [full audit report](PDFCHEMY_ANDROID_FULL_UI_UX_AUDIT.md).

Original PNG screenshots, XML hierarchies, TXT measurements, the PDF fixture, and the [capture helper](audit_device.py) are preserved. Markdown links provide an entry point to those original artifacts; they do not replace them.

## Scope and provenance

| Item | Audited baseline |
|---|---|
| Date | 4 October 2026 |
| Repository | `Shrinkpdf`, HEAD `de053371333a773157ad66838d726071e32d1cab` |
| Application | PDFchemy 2.0.6, versionCode 13; min SDK 24; target/compile SDK 36 |
| Android modules | `app-host`, `pdf-ipc`, `pdf-jail`, `pdf-renderer`; desktop UI excluded |
| Runtime | Existing debug APK on API 36 emulator `SecurityApi36`, software GPU `swiftshader_indirect` |
| APK identity | Installed APK matched local `app-host/build/outputs/apk/debug/app-host-debug.apk`; SHA-256 `D2447D43D1BB784254F437D504292990269E6579AE642D599CE85BA2C5C55E5B` |
| Build limit | No rebuild/install occurred. Matching installed/local artifacts does not establish a reproducible build from the audited commit. |
| Harness | Existing DEBUG `isScreenshotRun` bypasses first-run consent/onboarding, treats the session as Premium, suppresses ads, and supplies a compression-selection asset. |
| Actual pickers | Android document pickers were exercised for Editor, Merge, and Organizer. Compression selection used the harness asset. |
| Fixtures | Copies of existing feedback/production PDFs in emulator Downloads; a seven-page feedback PDF for Reader/Organizer; a 120-page Annual Report asset for compression; an intentionally truncated, script-free 96-byte PDF for recovery. Original workspace PDFs were untouched. |
| Display coverage | Approximately 411dp phone; simulated 320dp phone; font scale 1.0/2.0; actual landscape rotation; simulated 1707×1067dp large landscape display; light/dark dashboard. Display/font/rotation settings were restored afterward. |

Evidence labels in the full report:

- **R:** observed runtime or captured hierarchy.
- **C:** confirmed implementation.
- **C-condition:** confirmed code branch whose trigger frequency or exported effect still needs reproduction.
- **O:** observation requiring investigation, not an established defect.

Protected document surfaces use `FLAG_SECURE`. Their PNG captures can be black even when the document surface is functioning. The audit used XML hierarchy and source inspection for those surfaces and did not bypass capture protection. A black image is not proof of failed rendering.

Not runtime validated: physical phones/tablets/foldables, low-memory kill/restart, real ads, Play billing, complete TalkBack traversal, reduced-motion behavior, export-coordinate accuracy, a broader damaged/encrypted/provider-failure corpus, safe long-operation cancellation, or every conversion format.

## Workflow A — Compression and output handoff

| Evidence | What the validated capture establishes |
|---|---|
| [04 compression entry](evidence/04-compress-initial.png) · [hierarchy](evidence/04-compress-initial.xml) | Clear file-first empty state. |
| [06 hardware safeguard](evidence/06-current.png) · [hierarchy](evidence/06-current.xml) | A modal for the 120-page harness asset: estimated 33MB versus available 182MB; Split First and Proceed Anyway actions. |
| [07 default controls](evidence/07-compress-defaults.png) · [hierarchy](evidence/07-compress-defaults.xml) | Main-screen compression options after input selection. |
| [08 lower controls](evidence/08-compress-lower.png) · [hierarchy](evidence/08-compress-lower.xml) | Run action below technical controls, reached after a full vertical swipe. |
| [09 save picker](evidence/09-save-picker.png) · [hierarchy](evidence/09-save-picker.xml) | Editable filename and save destination before processing. |
| [10 processing](evidence/10-compress-operation.png) · [hierarchy](evidence/10-compress-operation.xml) | Shared processing overlay. |
| [11 completion](evidence/11-back-during-operation.png) · [hierarchy](evidence/11-back-during-operation.xml) | Successful result with Share/OK; no direct Open action. |

Capture 11's filename records an exploratory action label. It does **not** prove that Back canceled the operation. The observed good compression filename came from the harness path, which bypasses ordinary staging; it does not disprove source-derived naming defects on ordinary inputs.

## Workflow B — Merge discovery, selection, order, and save

| Evidence | What the validated capture establishes |
|---|---|
| [02 dashboard](evidence/02-dashboard.png) · [hierarchy](evidence/02-dashboard.xml) | Category-first access without a tool-search control. The missing search implementation is also source-confirmed in the full report. |
| [29 Merge entry](evidence/29-merge-empty.png) · [hierarchy](evidence/29-merge-empty.xml) | Empty Merge workspace and selection entry. |
| [31 multi-document picker](evidence/31-multi-document-picker.png) · [hierarchy](evidence/31-multi-document-picker.xml) | Actual Android document selection. |
| [32 first selected file](evidence/32-multi-select-first.png) · [33 two selected files](evidence/33-multi-select-two.png) | Multi-selection sequence in the native picker. |
| [35 workspace](evidence/35-merge-workspace.png) · [hierarchy](evidence/35-merge-workspace.xml) | Visible filenames, removal/addition controls, and explicit ordering controls. |
| [36 reordered workspace](evidence/36-merge-reordered.png) · [hierarchy](evidence/36-merge-reordered.xml) | Arrow reordering changed the file order. This is not a runtime test of drag behavior. |
| [37 save picker](evidence/37-merge-save.png) · [hierarchy](evidence/37-merge-save.xml) | Editable suggested Merge filename and destination. |
| [38 processing hierarchy](evidence/38-merge-processing.xml) | Merge displays “Compressing PDF...” during processing. |
| [55 later Downloads hierarchy](evidence/55-organizer-picker.xml) | Merged output exists in Downloads. |

The requested search step could not run because tool search is absent. The shared result's Share action is source-confirmed, but the Merge result-share chooser was not exercised. Do not describe Workflow B as completely validated through sharing.

## Workflow C — Reader, page navigation, and edited-session departure

| Evidence | What the validated capture establishes |
|---|---|
| [16 document picker](evidence/16-document-picker.png) · [hierarchy](evidence/16-document-picker.xml) | Actual SAF input selection for the editor/Reader surface. |
| [19 settled Reader hierarchy](evidence/19-reader-settled.xml) | Persistent Reader/editor control set. Its protected PNG is not used to assess PDF image quality. |
| [20 next-page hierarchy](evidence/20-reader-next.xml) | Page navigation and rendering state after Next. |
| [26 added text hierarchy](evidence/26-added-text.xml) | Validated text annotation added to the session. |
| [27 departure hierarchy](evidence/27-leave-edited-document.xml) | Back leaves the edited document without a save/discard decision. |
| [28 fresh-editor hierarchy](evidence/28-fresh-editor.xml) | Reopening Editor returns to empty source selection. |

The external VIEW route is source-verified. A direct ADB external VIEW attempt failed on shell URI permission, so external opening itself is not a demonstrated app failure or a completed runtime entry test. Runtime Reader testing used actual SAF selection.

PDF search and the current-document Reader-to-tool handoff are absent in the audited full-layout Reader implementation. The existing report distinguishes those capability/code findings from gestures that were actually exercised.

## Workflow D — Organizer workspace, deletion, and copy save

| Evidence | What the validated capture establishes |
|---|---|
| [53 lower Organize category](evidence/53-organize-lower.png) · [hierarchy](evidence/53-organize-lower.xml) | Organizer discovery in the category list. |
| [54 Organizer entry hierarchy](evidence/54-organizer-empty.xml) | File-first empty state. |
| [55 Organizer picker](evidence/55-organizer-picker.png) · [hierarchy](evidence/55-organizer-picker.xml) | Actual document selection. |
| [57 ready workspace hierarchy](evidence/57-organizer-ready.xml) | Seven-page workspace and its exposed control set; Undo/Redo absent. |
| [60 deleted-page hierarchy](evidence/60-organizer-deleted.xml) | Page count changes from seven to six. |
| [61 copy-save picker](evidence/61-organizer-save.png) · [hierarchy](evidence/61-organizer-save.xml) | Explicit filename/destination for a saved copy. |
| [63 result hierarchy](evidence/63-organizer-result.xml) | Copy save completes with Share/OK. |

Undo/Redo steps cannot be validated as successful because the controls/history are absent. The exported page content was not inspected; conditional failed-thumbnail page omission remains a source-branch finding, not a reproduced exported-output comparison.

## Workflow E — Damaged-document explanation and recovery

| Evidence | What the validated capture establishes |
|---|---|
| [Script-free truncated PDF fixture](fixtures/intentionally-damaged.pdf) | The intentionally damaged input used for the recovery test. |
| [68 error hierarchy](evidence/68-damaged-error.xml) | That damaged PDF is described as containing embedded scripts. |
| [69 recovery hierarchy](evidence/69-damaged-recovery.xml) | OK returns to source selection without a cause-specific explanation. |

This validates one damaged-document case. It does not establish behavior for all malformed PDFs, encrypted documents, wrong passwords, revoked provider grants, or write failures. Those wider cases remain in the report's validation matrix.

## Workflow F — Progress and cancellation boundaries

The [compression processing capture](evidence/10-compress-operation.xml) and [Merge processing capture](evidence/38-merge-processing.xml) establish the shared visible processing state. The missing user Cancel control and absent shared phase/page payload are source-confirmed in the full report.

No safe cancel-to-return journey was executed. Exploratory Back timing does not prove cancellation, touch-through, output cleanup, or a backend-safe interruption boundary. The existing worker abort/cleanup implementation is not sufficient evidence that every operation is safely cancelable.

## Theme, font, navigation, and device captures

| Evidence | Validated conclusion |
|---|---|
| [02 dashboard](evidence/02-dashboard.png) · [13 dark dashboard](evidence/13-dashboard-dark.png) | Existing light/dark visual language supports preserving the design baseline. |
| [41 normal-phone font 2.0](evidence/41-dashboard-font-200.png) · [hierarchy](evidence/41-dashboard-font-200.xml) | Dashboard descriptions truncate at enlarged text; identifying titles remain readable at the regular width. |
| [42 Settings font 2.0](evidence/42-settings-font-200.png) · [hierarchy](evidence/42-settings-font-200.xml) | Settings remains usable in this tested state. Do not report large-font Settings failure from source risk alone. |
| [43 Settings system Back hierarchy](evidence/43-settings-system-back.xml) | System Back exits to the launcher, unlike the toolbar's Home destination. Premium system Back is source-traced, not separately exercised. |
| [45 320dp phone/font 2.0](evidence/45-small-phone-font-200.png) · [hierarchy](evidence/45-small-phone-font-200.xml) | Identifying category-title truncation, including “Compress” becoming “Comp…”. |
| [48 actual landscape](evidence/48-phone-landscape.png) · [hierarchy](evidence/48-phone-landscape.xml) | Home remains scrollable; only the first category row is above the fold. This is a density tradeoff, not broken navigation. |
| [49 simulated large display](evidence/49-tablet-dashboard.png) · [hierarchy](evidence/49-tablet-dashboard.xml) | Bounded, centered dashboard rather than uncontrolled stretching. Empty margins alone are not a defect. |

Physical-device posture handling, foldables, precise touch target behavior, complete screen-reader traversal, real haptic strength, and reduced-motion behavior were not established by these captures.

## Raw Reader measurements and interpretation

The report's cited frame sample is [20 Reader Next gfxinfo](evidence/20-reader-next-gfxinfo.txt), collected after resetting frame statistics and triggering Next:

| Metric | Recorded value |
|---|---|
| Frames rendered | 198 |
| Janky frames | 34, or 17.17% |
| Frame percentiles p50 / p90 / p95 / p99 | 31ms / 53ms / 61ms / 150ms |
| Slow UI-thread frames | 27 |
| Slow bitmap uploads | 7 |

This is evidence of poor frame pacing in this debug/software-emulator test environment. The sample includes the transition/loading indicator; it is not an isolated page-render latency, a physical-device FPS result, a per-gesture latency measurement, or proof that one component caused every slow frame. The raw dump's legacy counters and software-GPU tail should not be substituted for a hardware-device verdict.

Supporting raw snapshots are retained:

- [18 ready gfxinfo](evidence/18-reader-ready-gfxinfo.txt) and [meminfo](evidence/18-reader-ready-meminfo.txt).
- [19 settled gfxinfo](evidence/19-reader-settled-gfxinfo.txt) and [meminfo](evidence/19-reader-settled-meminfo.txt).
- [20 Next meminfo](evidence/20-reader-next-meminfo.txt).

These memory snapshots do not prove an out-of-memory failure, leak, or memory-pressure recovery behavior. Capture/UIAutomator dump duration is not operation time. Main-thread decoding and thumbnail-loading behavior have separate source references in the full report; retain that distinction when prioritizing future profiling.

## Exploratory captures are not proof

The evidence directory also preserves unsuccessful taps, stale dumps, loading states, and ambiguous timing attempts. Their presence is useful provenance; their filenames are not validated assertions.

- [23 annotation-added attempt](evidence/23-annotation-added.xml), [24 Back attempt](evidence/24-back-with-unsaved.xml), and [25 return attempt](evidence/25-return-editor.xml) do **not** establish annotation loss. Use validated captures 26–28 instead.
- [46 landscape attempt](evidence/46-phone-landscape.png) and [47 rotation attempt](evidence/47-landscape-verified.png) were still portrait and are **not** landscape proof. Use capture 48.
- [11 Back-labeled completion capture](evidence/11-back-during-operation.xml) establishes the resulting completion dialog, **not** successful cancellation.
- Other captures omitted from the validated workflow tables are retained artifacts, not additional findings. Follow the full report's evidence index rather than inferring behavior from an exploratory filename.

No runtime claim is made about ad-induced geometry jumps, gesture theft, real billing transactions, destructive original-file overwrites, or low-memory state loss. Review those separately before implementation sign-off.
